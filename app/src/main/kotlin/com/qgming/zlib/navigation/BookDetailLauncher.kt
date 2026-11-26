package com.qgming.zlib.navigation

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.itgsa.animation.sharedview.ViewAnimationConfig
import com.itgsa.animation.sharedview.ViewAnimationExtras
import com.itgsa.animation.sharedview.ViewAnimationListener
import com.itgsa.animation.sharedview.ViewAnimationManager
import com.itgsa.animation.sharedview.ViewAnimationType
import com.qgming.zlib.BookDetailActivity
import com.qgming.zlib.core.designsystem.ZlibBookCoverSource
import com.qgming.zlib.core.designsystem.ZlibBookOpenRequest
import com.qgming.zlib.core.model.Book
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.WeakHashMap
import kotlinx.coroutines.CancellationException

/** The SDK owns Activity transitions; no SDK types escape into feature modules. */
internal object BookDetailLauncher {
    const val EXTRA_ID = "book.id"
    const val EXTRA_HASH = "book.hash"
    private const val EXTRA_TITLE = "book.title"
    private const val EXTRA_AUTHORS = "book.authors"
    private const val EXTRA_COVER = "book.cover"
    private const val EXTRA_SESSION = "book.transition"

    private class Session(val token: String, owner: Activity, source: ZlibBookCoverSource?) {
        val owner = WeakReference(owner)
        val source = WeakReference(source)
        var bitmap: Bitmap? = null
        var animated = false
    }

    // Guarded by lock because ROM animation callbacks may invoke listeners off the main thread.
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessions = mutableMapOf<String, Session>()
    private val activeSources = WeakHashMap<Activity, String>()

    fun isSupported(): Boolean = sdkOrNull { ViewAnimationManager.isViewAnimationSupported() > 0 } == true

    suspend fun open(activity: Activity, request: ZlibBookOpenRequest) {
        val session = synchronized(lock) {
            if (activeSources.containsKey(activity) || activity.isFinishing || activity.isDestroyed) return
            Session(UUID.randomUUID().toString(), activity, request.source).also { created ->
                sessions[created.token] = created
                activeSources[activity] = created.token
            }
        }
        try {
            val book = request.book
            val intent = Intent(activity, BookDetailActivity::class.java)
                .putExtra(EXTRA_ID, book.id)
                .putExtra(EXTRA_HASH, book.hash)
                .putExtra(EXTRA_TITLE, book.title.take(4_000))
                .putExtra(EXTRA_AUTHORS, book.authors.take(2_000))
                .putExtra(EXTRA_COVER, book.coverUrl.take(8_000))
                .putExtra(EXTRA_SESSION, session.token)

            val source = request.source
            val extras = if (isSupported()) sdkOrNull { ViewAnimationManager.getViewAnimationExtras() }.orEmpty() else emptyMap()
            val inWindow = SharedViewCapabilities.coordinates(extras[ViewAnimationExtras.EXTRA_BOUNDS]) == SharedViewCoordinates.WINDOW
            val bounds = source?.bounds(inWindow)
            var options: android.app.ActivityOptions? = null
            if (source != null && bounds != null && isSupported()) {
                val bitmap = try {
                    source.snapshot()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                val currentBounds = source.bounds(inWindow)
                if (bitmap != null && !bitmap.isRecycled && currentBounds != null) {
                    session.bitmap = bitmap
                    val config = ViewAnimationConfig().setColor(source.backgroundColor).toBundle()
                    putSupportedFallback(config, extras)
                    options = sdkOrNull {
                        ViewAnimationManager.makeViewAnimationOptions(bitmap, currentBounds, source.radiusPx, config, listener(session.token))
                    }
                }
            }
            if (activity.isFinishing || activity.isDestroyed) {
                release(session.token)
                return
            }
            session.animated = options != null
            if (options == null) {
                session.bitmap = null
                try {
                    activity.startActivity(intent)
                } finally {
                    // Non-animated transitions do not keep a return snapshot; unblock the source immediately.
                    release(session.token)
                }
            } else {
                try {
                    activity.startActivity(intent, options.toBundle())
                } catch (_: RuntimeException) {
                    sdkOrNull { ViewAnimationManager.cancelCurrentViewAnimation() }
                    session.animated = false
                    session.bitmap = null
                    try {
                        activity.startActivity(intent)
                    } finally {
                        release(session.token)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            release(session.token)
            throw cancelled
        } catch (_: RuntimeException) {
            release(session.token)
        } catch (error: Exception) {
            release(session.token)
            throw error
        }
    }

    fun preview(intent: Intent): Book? {
        val id = intent.getStringExtra(EXTRA_ID)?.takeIf { it.isNotBlank() } ?: return null
        val hash = intent.getStringExtra(EXTRA_HASH)?.takeIf { it.isNotBlank() } ?: return null
        return Book(
            id = id,
            hash = hash,
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            authors = intent.getStringExtra(EXTRA_AUTHORS).orEmpty(),
            coverUrl = intent.getStringExtra(EXTRA_COVER).orEmpty(),
        )
    }

    fun prepareReturn(activity: Activity) {
        val token = activity.intent.getStringExtra(EXTRA_SESSION)
        val session = synchronized(lock) { sessions[token]?.takeIf { it.animated } } ?: return
        sdkOrNull {
            val extras = ViewAnimationManager.getViewAnimationExtras().orEmpty()
            val inWindow = SharedViewCapabilities.coordinates(extras[ViewAnimationExtras.EXTRA_BOUNDS]) == SharedViewCoordinates.WINDOW
            val source = session.source.get()
            val bounds = source?.bounds(inWindow)
            val config = Bundle()
            if (bounds != null) {
                config.putParcelable(ViewAnimationExtras.EXTRA_BOUNDS, bounds)
                config.putFloat(ViewAnimationExtras.EXTRA_RADIUS, source.radiusPx)
                config.putInt(ViewAnimationExtras.EXTRA_COLOR, source.backgroundColor)
            } else {
                // Do not shrink toward a detached or invisible tile's stale coordinates.
                putSupportedBackType(config, extras)
            }
            putSupportedFallback(config, extras)
            ViewAnimationManager.overrideViewAnimationTransition(activity, config)
        }
    }

    fun release(intent: Intent) = release(intent.getStringExtra(EXTRA_SESSION))

    private fun release(token: String?) {
        if (token == null) return
        synchronized(lock) {
            val session = sessions.remove(token) ?: return
            session.owner.get()?.let { owner ->
                if (activeSources[owner] == token) activeSources.remove(owner)
            }
        }
        // ROM may still own the Bitmap during the closing frames; never recycle it here.
    }

    private fun listener(token: String) = object : ViewAnimationListener {
        override fun onAnimationStart(animMode: Int) = Unit
        override fun onAnimationEnd(animMode: Int) {
            if (animMode == ViewAnimationManager.ANIM_MODE_BACK) mainHandler.post { release(token) }
        }
        override fun onAnimationCancel(animMode: Int) {
            if (animMode == ViewAnimationManager.ANIM_MODE_BACK) mainHandler.post { release(token) }
        }
    }

    private fun putSupportedBackType(bundle: Bundle, extras: Map<String, String>) {
        val declaration = extras[ViewAnimationExtras.EXTRA_BACK_ANIM_TYPE]
        when {
            SharedViewCapabilities.supportsAnimation(declaration, "VIEW_ANIM_FOLLOW_SYSTEM") ->
                bundle.putInt(ViewAnimationExtras.EXTRA_BACK_ANIM_TYPE, ViewAnimationType.VIEW_ANIM_FOLLOW_SYSTEM)
            SharedViewCapabilities.supportsAnimation(declaration, "VIEW_ANIM_FADE_OUT") ->
                bundle.putInt(ViewAnimationExtras.EXTRA_BACK_ANIM_TYPE, ViewAnimationType.VIEW_ANIM_FADE_OUT)
        }
    }

    private fun putSupportedFallback(bundle: Bundle, extras: Map<String, String>) {
        val declaration = extras[ViewAnimationExtras.EXTRA_FALLBACK_ANIM_TYPE]
        if (SharedViewCapabilities.supportsAnimation(declaration, "VIEW_ANIM_EXPLODE_OUT")) {
            bundle.putInt(ViewAnimationExtras.EXTRA_FALLBACK_ANIM_TYPE, ViewAnimationType.VIEW_ANIM_EXPLODE_OUT)
        }
    }

    private inline fun <T> sdkOrNull(block: () -> T): T? = try {
        block()
    } catch (_: RuntimeException) {
        null
    } catch (_: LinkageError) {
        null
    }
}
