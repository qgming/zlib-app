package com.qgming.zlib.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import com.qgming.zlib.R
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.model.ShareImageBook
import com.qgming.zlib.core.model.ShareImageContent
import com.qgming.zlib.core.model.ShareImageKind
import kotlin.math.max

/** Synchronous drawing only. The caller owns decoding, dispatching and all input bitmaps. */
internal class ShareImageRenderer(private val context: Context) {
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val semibold = Typeface.create(regular, 600, false)

    fun render(content: ShareImageContent, covers: List<Bitmap?>): Bitmap {
        val body = when (content.kind) {
            ShareImageKind.Book -> measureBook(content, covers.firstOrNull())
            ShareImageKind.Booklist -> measureBooklist(content, covers)
        }
        val brand = text(
            context.getString(TextR.string.zlib_app_name),
            size = 32f,
            width = CONTENT_WIDTH - FOOTER_LOGO_SIZE - FOOTER_LOGO_GAP,
            maxLines = 2,
            color = INK,
            weight = semibold,
        )
        val footerHeight = max(FOOTER_LOGO_SIZE, brand.height)
        val page = ShareImageLayout.page(body.bottom, footerHeight)
        val image = createBitmap(ShareImageLayout.WIDTH, page.height)
        image.density = Bitmap.DENSITY_NONE
        val canvas = Canvas(image)
        canvas.drawColor(BASE)
        canvas.withTranslation(page.bodyLeft, 0f) {
            scale(page.bodyScale, page.bodyScale)
            body.draw(this)
        }
        drawBrand(canvas, brand, page.footerTop.toFloat(), footerHeight)
        return image
    }

    private fun measureBook(content: ShareImageContent, cover: Bitmap?): Body {
        val operations = mutableListOf<(Canvas) -> Unit>()
        val book = content.books.firstOrNull() ?: ShareImageBook(content.title, "", "")
        val stage = RectF(MARGIN, TOP_SPACE, WIDTH - MARGIN, TOP_SPACE + 620f)
        operations += { canvas ->
            canvas.drawRoundRect(stage, 24f, 24f, fill(MIST))
            drawCover(canvas, book, cover, (WIDTH - BOOK_COVER_WIDTH) / 2f, TOP_SPACE + 40f, BOOK_COVER_WIDTH)
        }
        var y = stage.bottom + 48f
        val title = text(
            content.title.ifBlank { book.title }.ifBlank { context.getString(TextR.string.text_untitled) },
            size = 56f,
            width = CONTENT_WIDTH,
            maxLines = 4,
            weight = semibold,
        )
        operations.addText(title, MARGIN, y)
        y += title.height
        if (book.authors.isNotBlank()) {
            val authors = text(book.authors, 34f, CONTENT_WIDTH, 3, MUTED)
            y += 18f
            operations.addText(authors, MARGIN, y)
            y += authors.height
        }

        if (content.fields.isNotEmpty()) {
            y += 40f
            val panelTop = y
            val innerWidth = CONTENT_WIDTH - METADATA_PADDING * 2
            val columnWidth = (innerWidth - METADATA_COLUMN_GAP) / 2
            val rows = content.fields.chunked(2).map { fields ->
                fields.map { field ->
                    Field(
                        label = text(field.label, 26f, columnWidth, 2, JADE, semibold),
                        value = text(field.value, 30f, columnWidth, 2),
                    )
                }
            }
            val rowHeights = rows.map { fields -> fields.maxOf { it.label.height } + 8 + fields.maxOf { it.value.height } }
            val panelHeight = METADATA_PADDING * 2 + rowHeights.sum() + METADATA_ROW_GAP * (rows.size - 1)
            val panel = RectF(MARGIN, panelTop, WIDTH - MARGIN, panelTop + panelHeight)
            operations += { canvas -> canvas.drawRoundRect(panel, 24f, 24f, fill(MIST)) }
            var rowTop = panelTop + METADATA_PADDING
            rows.forEachIndexed { rowIndex, fields ->
                val valueTop = rowTop + fields.maxOf { it.label.height } + 8f
                fields.forEachIndexed { column, field ->
                    val left = MARGIN + METADATA_PADDING + column * (columnWidth + METADATA_COLUMN_GAP)
                    operations.addText(field.label, left, rowTop)
                    operations.addText(field.value, left, valueTop)
                }
                rowTop += rowHeights[rowIndex] + METADATA_ROW_GAP
            }
            y = panel.bottom
        }

        if (content.description.isNotBlank()) {
            y += 40f
            val heading = text(context.getString(TextR.string.text_description), 28f, CONTENT_WIDTH, 1, JADE, semibold)
            operations.addText(heading, MARGIN, y)
            y += heading.height + 16f
            val description = text(content.description, 34f, CONTENT_WIDTH, 6, MUTED, lineSpacing = 5f)
            operations.addText(description, MARGIN, y)
            y += description.height
        }
        return Body(y, operations)
    }

    private fun measureBooklist(content: ShareImageContent, covers: List<Bitmap?>): Body {
        val operations = mutableListOf<(Canvas) -> Unit>()
        var y = TOP_SPACE
        val label = text(context.getString(TextR.string.text_share_booklist), 28f, CONTENT_WIDTH, 1, JADE, semibold)
        operations.addText(label, MARGIN, y)
        y += label.height + 18f
        val title = text(
            content.title.ifBlank { context.getString(TextR.string.text_untitled) },
            60f, CONTENT_WIDTH, 4, weight = semibold,
        )
        operations.addText(title, MARGIN, y)
        y += title.height
        content.totalBooks?.let { total ->
            val count = text(context.resources.getQuantityString(TextR.plurals.share_booklist_total, total, total), 32f, CONTENT_WIDTH, 2, MUTED)
            y += 20f
            operations.addText(count, MARGIN, y)
            y += count.height
        }
        if (content.previewIsPartial) {
            val preview = text(
                context.resources.getQuantityString(TextR.plurals.share_booklist_preview, content.books.size, content.books.size),
                30f, CONTENT_WIDTH, 2, JADE,
            )
            y += 16f
            operations.addText(preview, MARGIN, y)
            y += preview.height
        }
        y += 48f
        val books = content.books.mapIndexed { index, book ->
            GalleryBook(
                book = book,
                cover = covers.getOrNull(index),
                title = text(book.title.ifBlank { context.getString(TextR.string.text_untitled) }, 30f, GALLERY_COVER_WIDTH, 2, weight = semibold),
                authors = book.authors.takeIf(String::isNotBlank)?.let { text(it, 25f, GALLERY_COVER_WIDTH, 2, MUTED) },
            )
        }
        if (books.isEmpty()) {
            val empty = text(
                context.getString(TextR.string.text_share_empty_booklist),
                34f, CONTENT_WIDTH - 64, 3, MUTED, alignment = Layout.Alignment.ALIGN_CENTER,
            )
            val emptyHeight = max(240, empty.height + 96)
            val panel = RectF(MARGIN, y, WIDTH - MARGIN, y + emptyHeight)
            operations += { canvas -> canvas.drawRoundRect(panel, 24f, 24f, fill(MIST)) }
            operations.addText(empty, MARGIN + 32f, y + (emptyHeight - empty.height) / 2f)
            y += emptyHeight
        } else {
            books.chunked(GALLERY_COLUMNS).forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) y += 44f
                val rowTop = y
                val titleTop = rowTop + GALLERY_COVER_HEIGHT + 18f
                val titleHeight = row.maxOf { it.title.height }
                val authorsHeight = row.maxOf { it.authors?.height ?: 0 }
                val authorsTop = titleTop + titleHeight + if (authorsHeight > 0) 8f else 0f
                row.forEachIndexed { column, item ->
                    val left = MARGIN + column * (GALLERY_COVER_WIDTH + GALLERY_GAP)
                    operations += { canvas -> drawCover(canvas, item.book, item.cover, left, rowTop, GALLERY_COVER_WIDTH.toFloat()) }
                    operations.addText(item.title, left, titleTop)
                    item.authors?.let { operations.addText(it, left, authorsTop) }
                }
                y = authorsTop + authorsHeight
            }
        }
        return Body(y, operations)
    }

    private fun drawCover(canvas: Canvas, book: ShareImageBook, bitmap: Bitmap?, left: Float, top: Float, width: Float) {
        val bounds = RectF(left, top, left + width, top + width * 1.5f)
        val shadow = fill(BASE).apply { setShadowLayer(14f, 0f, 7f, Color.argb(24, 24, 47, 55)) }
        canvas.drawRoundRect(bounds, 8f, 8f, shadow)
        val save = canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(bounds, 8f, 8f, Path.Direction.CW) })
        var temporary: Bitmap? = null
        try {
            val original = bitmap?.takeUnless { it.isRecycled }
            val source = if (original?.config == Bitmap.Config.HARDWARE) {
                original.copy(Bitmap.Config.ARGB_8888, false).also { temporary = it }
            } else {
                original
            }
            if (source != null && source.width > 0 && source.height > 0) {
                val scale = max(width / source.width, bounds.height() / source.height)
                val transform = Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(left + (width - source.width * scale) / 2f, top + (bounds.height() - source.height * scale) / 2f)
                }
                canvas.drawBitmap(source, transform, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            } else {
                drawClassicCover(canvas, book, bounds)
            }
        } finally {
            temporary?.recycle()
            canvas.restoreToCount(save)
        }
    }

    private fun drawClassicCover(canvas: Canvas, book: ShareImageBook, bounds: RectF) {
        canvas.drawRect(bounds, fill(CLOTH))
        val weave = fill(Color.argb(10, 255, 255, 255)).apply { strokeWidth = 1f }
        var line = bounds.left + 3f
        while (line < bounds.right) {
            canvas.drawLine(line, bounds.top, line, bounds.bottom, weave)
            line += 6f
        }
        line = bounds.top + 3f
        while (line < bounds.bottom) {
            canvas.drawLine(bounds.left, line, bounds.right, line, weave)
            line += 6f
        }
        canvas.drawRect(bounds.left + 8f, bounds.top, bounds.left + 16f, bounds.bottom, fill(Color.argb(28, 0, 0, 0)))
        val frame = RectF(bounds).apply { inset(20f, 20f) }
        canvas.drawRect(frame, fill(GOLD).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f })
        val insideWidth = (bounds.width() - 72f).toInt()
        val title = text(
            book.title.ifBlank { context.getString(TextR.string.text_untitled) },
            bounds.width() * 0.094f, insideWidth, 5, CREAM, semibold,
            alignment = Layout.Alignment.ALIGN_CENTER,
        )
        val authors = book.authors.takeIf(String::isNotBlank)?.let {
            text(it, bounds.width() * 0.062f, insideWidth, 2, GOLD, alignment = Layout.Alignment.ALIGN_CENTER)
        }
        val blockHeight = title.height + if (authors != null) 36 + authors.height else 0
        val textLeft = bounds.left + (bounds.width() - insideWidth) / 2f
        val titleTop = bounds.top + (bounds.height() - blockHeight) / 2f
        drawText(canvas, title, textLeft, titleTop)
        if (authors != null) {
            val ruleY = titleTop + title.height + 18f
            val ruleLeft = bounds.centerX() - bounds.width() * 0.09f
            canvas.drawLine(ruleLeft, ruleY, bounds.centerX() + bounds.width() * 0.09f, ruleY, fill(GOLD).apply { strokeWidth = 1f })
            drawText(canvas, authors, textLeft, titleTop + title.height + 36f)
        }
    }

    private fun drawBrand(canvas: Canvas, brand: StaticLayout, top: Float, height: Int) {
        val textWidth = (0 until brand.lineCount).maxOfOrNull(brand::getLineWidth) ?: 0f
        val rowWidth = FOOTER_LOGO_SIZE + FOOTER_LOGO_GAP + textWidth
        val left = (WIDTH - rowWidth) / 2f
        ContextCompat.getDrawable(context, R.drawable.ic_zhishi_foreground)?.mutate()?.let { logo ->
            val logoTop = top + (height - FOOTER_LOGO_SIZE) / 2f
            logo.setBounds(left.toInt(), logoTop.toInt(), (left + FOOTER_LOGO_SIZE).toInt(), (logoTop + FOOTER_LOGO_SIZE).toInt())
            logo.draw(canvas)
        }
        drawText(canvas, brand, left + FOOTER_LOGO_SIZE + FOOTER_LOGO_GAP, top + (height - brand.height) / 2f)
    }

    private fun text(
        value: String,
        size: Float,
        width: Int,
        maxLines: Int,
        color: Int = INK,
        weight: Typeface = regular,
        alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
        lineSpacing: Float = 2f,
    ): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = size
            typeface = weight
            this.color = color
            textLocales = context.resources.configuration.locales
        }
        return StaticLayout.Builder.obtain(value, 0, value.length, paint, width)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .setUseLineSpacingFromFallbacks(true)
            .setLineSpacing(lineSpacing, 1.08f)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setEllipsizedWidth(width)
            .build()
    }

    private fun MutableList<(Canvas) -> Unit>.addText(layout: StaticLayout, left: Float, top: Float) {
        add { canvas -> drawText(canvas, layout, left, top) }
    }

    private fun drawText(canvas: Canvas, layout: StaticLayout, left: Float, top: Float) {
        canvas.withTranslation(left, top) { layout.draw(this) }
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private data class Body(val bottom: Float, val operations: List<(Canvas) -> Unit>) {
        fun draw(canvas: Canvas) = operations.forEach { it(canvas) }
    }

    private data class Field(val label: StaticLayout, val value: StaticLayout)
    private data class GalleryBook(val book: ShareImageBook, val cover: Bitmap?, val title: StaticLayout, val authors: StaticLayout?)

    private companion object {
        const val WIDTH = ShareImageLayout.WIDTH.toFloat()
        const val CONTENT_WIDTH = 920
        const val MARGIN = 80f
        const val TOP_SPACE = 64f
        const val BOOK_COVER_WIDTH = 360f
        const val METADATA_PADDING = 32
        const val METADATA_COLUMN_GAP = 32
        const val METADATA_ROW_GAP = 24
        const val GALLERY_COLUMNS = 3
        const val GALLERY_GAP = 28
        const val GALLERY_COVER_WIDTH = 288
        const val GALLERY_COVER_HEIGHT = 432
        // The original vector has transparent insets; this gives its visible artwork about 60 px.
        const val FOOTER_LOGO_SIZE = 96
        const val FOOTER_LOGO_GAP = 8
        val BASE: Int = Color.rgb(255, 255, 255)
        val MIST: Int = Color.rgb(242, 245, 244)
        val INK: Int = Color.rgb(24, 47, 55)
        val MUTED: Int = Color.rgb(97, 116, 122)
        val JADE: Int = Color.rgb(84, 117, 104)
        val CLOTH: Int = Color.rgb(32, 59, 67)
        val GOLD: Int = Color.rgb(200, 174, 121)
        val CREAM: Int = Color.rgb(242, 232, 206)
    }
}
