package com.qgming.zlib.core.data.update

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import androidx.documentfile.provider.DocumentFile
import com.qgming.zlib.core.data.download.DownloadDirectoryStore
import com.qgming.zlib.core.model.AppRelease
import com.qgming.zlib.core.model.compareReleaseVersions
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class UpdateApkStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val directory: DownloadDirectoryStore,
) {
    fun temporary(): File = File(context.filesDir, "updates").apply { mkdirs() }.let { File(it, "release.part") }
    fun ready(): File = File(context.filesDir, "updates/release.apk")
    private fun receipt() = AtomicFile(File(context.filesDir, "updates/ready.json"))

    fun publishReady(source: File, release: AppRelease, saved: Uri) {
        val target = ready().toPath()
        try {
            Files.move(source.toPath(), target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
        }
        val text = buildJsonObject {
            put("tag", JsonPrimitive(release.tag))
            put("title", JsonPrimitive(release.title))
            put("notes", JsonPrimitive(release.notes))
            put("page", JsonPrimitive(release.pageUrl))
            put("saved", JsonPrimitive(saved.toString()))
        }.toString()
        val atomic = receipt()
        try {
            val stream = atomic.startWrite()
            try {
                stream.write(text.toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (failure: IOException) {
                atomic.failWrite(stream)
                throw failure
            }
        } catch (failure: Exception) {
            discardReady()
            throw failure
        }
    }

    fun discardReady() {
        receipt().delete()
        ready().delete()
    }

    fun discardSaved(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
    }

    fun restoreReady(): Pair<AppRelease, String>? {
        if (!ready().isFile) return null
        return try {
            val root = receipt().openRead().bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
            fun field(name: String) = root.getValue(name).jsonPrimitive.content
            val release = AppRelease(field("tag"), field("notes"), field("page"), field("title"))
            validate(ready(), release)
            release to field("saved")
        } catch (_: Exception) {
            discardReady()
            null
        }
    }

    fun validate(file: File, release: AppRelease) {
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val archive = context.packageManager.getPackageArchiveInfo(file.path, flags) ?: throw IOException("Invalid APK")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        fun signatures(info: android.content.pm.PackageInfo): Set<String> = info.signingInfo?.apkContentsSigners.orEmpty()
            .map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
        if (!validUpdateIdentity(
                ApkIdentity(archive.packageName, archive.versionName.orEmpty(), archive.longVersionCode, signatures(archive)),
                ApkIdentity(installed.packageName, installed.versionName.orEmpty(), installed.longVersionCode, signatures(installed)), release.tag,
            )) throw IOException("APK identity mismatch")
    }

    suspend fun save(file: File, release: AppRelease): Uri {
        val selected = directory.ensureLoaded() ?: throw IOException("No download directory")
        val tree = DocumentFile.fromTreeUri(context, selected)?.takeIf { it.canWrite() } ?: throw IOException("Download directory unavailable")
        suspend fun ensureDestination() {
            currentCoroutineContext().ensureActive()
            if (directory.currentUri() != selected) throw IOException("Download directory changed")
        }
        ensureDestination()
        val tag = release.tag.replace(Regex("[^0-9A-Za-z._-]"), "_").take(80)
        val finalName = "zhishi-$tag-${System.currentTimeMillis()}.apk"
        val document = tree.createFile("application/octet-stream", "$finalName.part")
            ?: throw IOException("Cannot create update file")
        var fallback: DocumentFile? = null
        try {
            suspend fun copyTo(destination: DocumentFile) {
                context.contentResolver.openOutputStream(destination.uri, "wt")?.use { output ->
                    file.inputStream().buffered().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureDestination()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                        }
                    }
                } ?: throw IOException("Cannot write update file")
            }
            copyTo(document)
            ensureDestination()
            if (document.renameTo(finalName)) return document.uri
            val finalDocument = tree.createFile("application/vnd.android.package-archive", finalName)
                ?: throw IOException("Cannot finalize update file")
            fallback = finalDocument
            copyTo(finalDocument)
            ensureDestination()
            document.delete()
            return finalDocument.uri
        } catch (failure: Exception) {
            runCatching { document.delete() }
            runCatching { fallback?.delete() }
            throw failure
        }
    }
}

internal data class ApkIdentity(val packageName: String, val version: String, val versionCode: Long, val signers: Set<String>)

internal fun validUpdateIdentity(candidate: ApkIdentity, installed: ApkIdentity, tag: String): Boolean =
    candidate.packageName == installed.packageName && candidate.versionCode > installed.versionCode &&
        compareReleaseVersions(tag, candidate.version) == 0 && installed.signers.isNotEmpty() && candidate.signers == installed.signers
