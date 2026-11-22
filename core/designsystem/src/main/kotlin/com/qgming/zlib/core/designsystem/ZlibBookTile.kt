package com.qgming.zlib.core.designsystem

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qgming.zlib.core.model.Book
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ZlibBookTile(
    book: Book,
    onClick: (ZlibBookOpenRequest) -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val source = if (LocalZlibBookSnapshotsEnabled.current) rememberCoverSource() else null
    Column(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            role = Role.Button,
            onClickLabel = "查看图书详情",
            onLongClickLabel = if (onLongClick != null) "文件操作" else null,
            onClick = { onClick(ZlibBookOpenRequest(book, source)) },
            onLongClick = onLongClick,
        ),
    ) {
        ZlibCover(
            url = book.coverUrl,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .then(source?.modifier ?: Modifier),
            contentScale = ContentScale.Crop,
            title = book.title,
            authors = book.authors,
        )
        Text(
            text = book.title,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            maxLines = 1,
        )
    }
}

@Composable
private fun rememberCoverSource(): ComposeCoverSource {
    val layer = rememberGraphicsLayer()
    val radius = with(LocalDensity.current) { 8.dp.toPx() }
    val background = MiuixTheme.colorScheme.surface.toArgb()
    return remember(layer, radius, background) {
        ComposeCoverSource(layer, radius, background)
    }
}

private class ComposeCoverSource(
    private val layer: androidx.compose.ui.graphics.layer.GraphicsLayer,
    override val radiusPx: Float,
    override val backgroundColor: Int,
) : ZlibBookCoverSource {
    private var coordinates: LayoutCoordinates? = null
    val modifier: Modifier = Modifier.onGloballyPositioned { coordinates = it }.drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }

    override suspend fun snapshot(): android.graphics.Bitmap? {
        if (layer.isReleased || layer.size.width <= 0 || layer.size.height <= 0) return null
        return layer.toImageBitmap().asAndroidBitmap()
    }

    override fun bounds(inWindow: Boolean): android.graphics.Rect? = runCatching {
        val position = coordinates?.takeIf { it.isAttached } ?: return@runCatching null
        val offset = if (inWindow) position.localToWindow(Offset.Zero) else position.localToScreen(Offset.Zero)
        if (!offset.x.isFinite() || !offset.y.isFinite()) return@runCatching null
        // A recycled/detached/off-screen source cannot be a safe return target.
        val root = generateSequence(position) { it.parentLayoutCoordinates }.last()
        val visible = root.localBoundingBoxOf(position, clipBounds = true)
        if (visible.width < position.size.width - 1 || visible.height < position.size.height - 1) return@runCatching null
        android.graphics.Rect(
            offset.x.toInt(), offset.y.toInt(),
            (offset.x + position.size.width).toInt(), (offset.y + position.size.height).toInt(),
        ).takeUnless { it.isEmpty }
    }.getOrNull()
}
