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
import com.qgming.zlib.core.model.ShareImageField
import com.qgming.zlib.core.model.ShareImageKind
import kotlin.math.ceil
import kotlin.math.max

/** Color tokens for the exported share image in light and dark styles. */
internal data class SharePalette(
    val canvas: Int,
    val surface: Int,
    val chipFill: Int,
    val accentFill: Int,
    val ink: Int,
    val secondary: Int,
    val muted: Int,
    val accent: Int,
    val ratingColor: Int,
    val divider: Int,
    val coverBorder: Int,
    val shadowAmbient: Int,
    val shadowKey: Int,
    val classicCloths: List<Int>,
    val classicInk: Int,
    val classicGold: Int,
    val classicSpineShadow: Int,
) {
    companion object {
        val Light = SharePalette(
            canvas = 0xFFF7F6F2.toInt(),
            surface = 0xFFFFFFFF.toInt(),
            chipFill = 0xFFEEECE6.toInt(),
            accentFill = 0xFFE8F0FE.toInt(),
            ink = 0xFF17181C.toInt(),
            secondary = 0xFF555B66.toInt(),
            muted = 0xFF7D8490.toInt(),
            accent = 0xFF2F6FED.toInt(),
            ratingColor = 0xFFD9820E.toInt(),
            divider = 0xFFE4E2DC.toInt(),
            coverBorder = 0x1A17181C,
            shadowAmbient = 0x1217181C,
            shadowKey = 0x2617181C,
            classicCloths = listOf(
                0xFF293F3C.toInt(),
                0xFF2C3A50.toInt(),
                0xFF58383B.toInt(),
                0xFF4C4436.toInt(),
            ),
            classicInk = 0xFFF4EBD8.toInt(),
            classicGold = 0xFFC3AA77.toInt(),
            classicSpineShadow = 0x2E000000,
        )

        val Dark = SharePalette(
            canvas = 0xFF13151A.toInt(),
            surface = 0xFF1B1F27.toInt(),
            chipFill = 0xFF222731.toInt(),
            accentFill = 0xFF1E2C44.toInt(),
            ink = 0xFFF2F4F8.toInt(),
            secondary = 0xFFA6AEBC.toInt(),
            muted = 0xFF767F8F.toInt(),
            accent = 0xFF6B9EFF.toInt(),
            ratingColor = 0xFFF5B942.toInt(),
            divider = 0xFF2A2F3A.toInt(),
            coverBorder = 0x2AFFFFFF,
            shadowAmbient = 0x48000000,
            shadowKey = 0x80000000.toInt(),
            classicCloths = listOf(
                0xFF223735.toInt(),
                0xFF253246.toInt(),
                0xFF4A2F32.toInt(),
                0xFF40392D.toInt(),
            ),
            classicInk = 0xFFEEE5D1.toInt(),
            classicGold = 0xFFCEB582.toInt(),
            classicSpineShadow = 0x48000000,
        )

        fun of(darkTheme: Boolean): SharePalette = if (darkTheme) Dark else Light
    }
}

/** Synchronous drawing only. The caller owns decoding, dispatching and all input bitmaps. */
internal class ShareImageRenderer(private val context: Context) {
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create(regular, 500, false)
    private val semibold = Typeface.create(regular, 600, false)
    private val bold = Typeface.create(regular, 700, false)
    private val serif = Typeface.create("serif", Typeface.NORMAL)
    private val serifMedium = Typeface.create(serif, 500, false)

    fun render(content: ShareImageContent, covers: List<Bitmap?>, darkTheme: Boolean = false): Bitmap {
        val palette = SharePalette.of(darkTheme)
        val body = when (content.kind) {
            ShareImageKind.Book -> measureBook(content, covers.firstOrNull(), palette)
            ShareImageKind.Booklist -> measureBooklist(content, covers, palette)
        }
        val brand = text(
            context.getString(TextR.string.zlib_app_name),
            size = 30f,
            width = CONTENT_WIDTH - FOOTER_LOGO_SIZE - FOOTER_LOGO_GAP,
            maxLines = 2,
            color = palette.secondary,
            weight = semibold,
        )
        val footerHeight = max(FOOTER_LOGO_SIZE, brand.height) + FOOTER_RULE_GAP
        val page = ShareImageLayout.page(body.bottom, footerHeight)
        val image = createBitmap(ShareImageLayout.WIDTH, page.height)
        image.density = Bitmap.DENSITY_NONE
        val canvas = Canvas(image)
        canvas.drawColor(palette.canvas)
        canvas.withTranslation(page.bodyLeft, 0f) {
            scale(page.bodyScale, page.bodyScale)
            body.draw(this)
        }
        drawFooter(canvas, brand, page.footerTop.toFloat(), footerHeight, palette)
        return image
    }

    private fun measureBook(content: ShareImageContent, cover: Bitmap?, palette: SharePalette): Body {
        val operations = mutableListOf<(Canvas) -> Unit>()
        val book = content.books.firstOrNull() ?: ShareImageBook(content.title, "", "")

        // Hero cover directly on the page canvas with soft layered drop shadow (no outer box/card).
        val coverLeft = (WIDTH - BOOK_COVER_WIDTH) / 2f
        val coverTop = TOP_SPACE + 12f
        val coverHeight = BOOK_COVER_WIDTH * 1.5f
        operations += { canvas ->
            drawCover(canvas, book, cover, coverLeft, coverTop, BOOK_COVER_WIDTH, BOOK_COVER_RADIUS, palette)
        }
        var y = coverTop + coverHeight + 52f

        // Centered editorial title and muted author line.
        val titleText = content.title.ifBlank { book.title }.ifBlank { context.getString(TextR.string.text_untitled) }
        val title = text(
            titleText,
            size = 52f,
            width = CONTENT_WIDTH,
            maxLines = 4,
            color = palette.ink,
            weight = bold,
            alignment = Layout.Alignment.ALIGN_CENTER,
            lineSpacing = 4f,
        )
        operations.addText(title, MARGIN, y)
        y += title.height

        if (book.authors.isNotBlank()) {
            val authors = text(
                book.authors,
                size = 32f,
                width = CONTENT_WIDTH,
                maxLines = 3,
                color = palette.secondary,
                weight = medium,
                alignment = Layout.Alignment.ALIGN_CENTER,
                lineSpacing = 2f,
            )
            y += 18f
            operations.addText(authors, MARGIN, y)
            y += authors.height
        }

        // Split fields into highlight pills (rating, year, format, size) and structured publication rows.
        val highlightLabels = setOf(
            context.getString(TextR.string.text_publication_year),
            context.getString(TextR.string.text_current_format),
            context.getString(TextR.string.text_file_size),
        )
        val pills = buildList {
            if (content.rating.isNotBlank()) add(PillItem(label = "★ ${content.rating}", emphasized = true))
            content.fields.filter { it.label in highlightLabels }.forEach { field ->
                add(PillItem(label = field.value, emphasized = false))
            }
        }
        val detailFields = content.fields.filterNot { it.label in highlightLabels }

        if (pills.isNotEmpty()) {
            y += 30f
            y = addCenteredPills(operations, pills, y, palette)
        }

        if (detailFields.isNotEmpty() || content.description.isNotBlank()) {
            y += 40f
            val dividerY = y
            operations += { canvas ->
                canvas.drawLine(MARGIN, dividerY, WIDTH - MARGIN, dividerY, stroke(palette.divider, 1.5f))
            }
            y += 1.5f
        }

        if (content.description.isNotBlank()) {
            y += 36f
            val descHeading = text(
                context.getString(TextR.string.text_description),
                size = 26f,
                width = CONTENT_WIDTH - PANEL_PADDING * 2,
                maxLines = 1,
                color = palette.muted,
                weight = semibold,
            )
            val descBody = text(
                content.description,
                size = 31f,
                width = CONTENT_WIDTH - PANEL_PADDING * 2 - ACCENT_BAR_INSET,
                maxLines = 6,
                color = palette.secondary,
                weight = regular,
                lineSpacing = 10f,
            )
            val panelTop = y
            val panelHeight = PANEL_PADDING + descHeading.height + 16f + descBody.height + PANEL_PADDING
            val panel = RectF(MARGIN, panelTop, WIDTH - MARGIN, panelTop + panelHeight)
            val barTop = panelTop + PANEL_PADDING + descHeading.height + 20f
            val barBottom = panelTop + panelHeight - PANEL_PADDING - 4f
            operations += { canvas ->
                canvas.drawRoundRect(panel, PANEL_RADIUS, PANEL_RADIUS, fill(palette.surface))
                canvas.drawRoundRect(
                    RectF(MARGIN + PANEL_PADDING, barTop, MARGIN + PANEL_PADDING + 5f, max(barTop + 12f, barBottom)),
                    2.5f,
                    2.5f,
                    fill(palette.accent),
                )
            }
            operations.addText(descHeading, MARGIN + PANEL_PADDING, panelTop + PANEL_PADDING)
            operations.addText(
                descBody,
                MARGIN + PANEL_PADDING + ACCENT_BAR_INSET,
                panelTop + PANEL_PADDING + descHeading.height + 16f,
            )
            y = panel.bottom
        }

        if (detailFields.isNotEmpty()) {
            y += 36f
            val metaHeading = text(
                context.getString(TextR.string.text_publication_information),
                size = 26f,
                width = CONTENT_WIDTH - PANEL_PADDING * 2,
                maxLines = 1,
                color = palette.muted,
                weight = semibold,
            )
            val innerWidth = CONTENT_WIDTH - PANEL_PADDING * 2
            val labelColWidth = (innerWidth * 0.30f).toInt()
            val valueColWidth = innerWidth - labelColWidth - META_ROW_GAP
            val rows = detailFields.map { field ->
                FieldRow(
                    label = text(field.label, 27f, labelColWidth, 2, palette.muted, medium),
                    value = text(
                        field.value,
                        28f,
                        valueColWidth,
                        2,
                        palette.ink,
                        regular,
                        alignment = Layout.Alignment.ALIGN_OPPOSITE,
                    ),
                )
            }
            val panelTop = y
            var innerY = panelTop + PANEL_PADDING + metaHeading.height + 22f
            val rowPositions = mutableListOf<Triple<FieldRow, Float, Float>>()
            rows.forEachIndexed { index, row ->
                val rowHeight = max(row.label.height, row.value.height).toFloat()
                rowPositions += Triple(row, innerY, rowHeight)
                innerY += rowHeight + if (index < rows.lastIndex) META_ITEM_SPACING else 0f
            }
            val panelBottom = innerY + PANEL_PADDING
            val panel = RectF(MARGIN, panelTop, WIDTH - MARGIN, panelBottom)
            operations += { canvas ->
                canvas.drawRoundRect(panel, PANEL_RADIUS, PANEL_RADIUS, fill(palette.surface))
            }
            operations.addText(metaHeading, MARGIN + PANEL_PADDING, panelTop + PANEL_PADDING)
            rowPositions.forEachIndexed { index, (row, rowTop, rowHeight) ->
                operations.addText(row.label, MARGIN + PANEL_PADDING, rowTop + (rowHeight - row.label.height) / 2f)
                operations.addText(
                    row.value,
                    MARGIN + PANEL_PADDING + labelColWidth + META_ROW_GAP,
                    rowTop + (rowHeight - row.value.height) / 2f,
                )
                if (index < rowPositions.lastIndex) {
                    val sepY = rowTop + rowHeight + META_ITEM_SPACING / 2f
                    operations += { canvas ->
                        canvas.drawLine(
                            MARGIN + PANEL_PADDING,
                            sepY,
                            WIDTH - MARGIN - PANEL_PADDING,
                            sepY,
                            stroke(palette.divider, 1f),
                        )
                    }
                }
            }
            y = panel.bottom
        }

        return Body(y, operations)
    }

    private fun measureBooklist(content: ShareImageContent, covers: List<Bitmap?>, palette: SharePalette): Body {
        val operations = mutableListOf<(Canvas) -> Unit>()
        var y = TOP_SPACE

        // Subtle eyebrow pill tag ("Booklist / 书单").
        val eyebrowText = context.getString(TextR.string.text_share_booklist)
        val eyebrowPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = 24f
            typeface = semibold
            color = palette.accent
            textLocales = context.resources.configuration.locales
        }
        val eyebrowWidth = ceil(eyebrowPaint.measureText(eyebrowText)).toInt().coerceAtMost(CONTENT_WIDTH - 48)
        val eyebrowLayout = text(eyebrowText, 24f, max(1, eyebrowWidth), 1, palette.accent, semibold)
        val tagRect = RectF(
            MARGIN,
            y,
            MARGIN + eyebrowWidth + 36f,
            y + eyebrowLayout.height + 18f,
        )
        operations += { canvas ->
            canvas.drawRoundRect(tagRect, tagRect.height() / 2f, tagRect.height() / 2f, fill(palette.accentFill))
        }
        operations.addText(eyebrowLayout, tagRect.left + 18f, tagRect.top + 9f)
        y = tagRect.bottom + 24f

        // Bold booklist title.
        val title = text(
            content.title.ifBlank { context.getString(TextR.string.text_untitled) },
            size = 56f,
            width = CONTENT_WIDTH,
            maxLines = 4,
            color = palette.ink,
            weight = bold,
            lineSpacing = 4f,
        )
        operations.addText(title, MARGIN, y)
        y += title.height

        // Summary line combining total count and partial preview note cleanly.
        val summaryParts = buildList {
            content.totalBooks?.let { total ->
                add(context.resources.getQuantityString(TextR.plurals.share_booklist_total, total, total))
            }
            if (content.previewIsPartial) {
                add(context.resources.getQuantityString(TextR.plurals.share_booklist_preview, content.books.size, content.books.size))
            }
        }
        if (summaryParts.isNotEmpty()) {
            val summary = text(
                summaryParts.joinToString("  ·  "),
                size = 29f,
                width = CONTENT_WIDTH,
                maxLines = 2,
                color = palette.secondary,
                weight = medium,
            )
            y += 18f
            operations.addText(summary, MARGIN, y)
            y += summary.height
        }

        // Hairline divider separating header from showcase.
        y += 34f
        val dividerY = y
        operations += { canvas ->
            canvas.drawLine(MARGIN, dividerY, WIDTH - MARGIN, dividerY, stroke(palette.divider, 1.5f))
        }
        y += 42f

        if (content.books.isEmpty()) {
            val empty = text(
                context.getString(TextR.string.text_share_empty_booklist),
                size = 32f,
                width = CONTENT_WIDTH - 80,
                maxLines = 3,
                color = palette.muted,
                weight = medium,
                alignment = Layout.Alignment.ALIGN_CENTER,
            )
            val emptyHeight = max(240, empty.height + 112)
            val panel = RectF(MARGIN, y, WIDTH - MARGIN, y + emptyHeight)
            operations += { canvas ->
                canvas.drawRoundRect(panel, PANEL_RADIUS, PANEL_RADIUS, fill(palette.surface))
            }
            operations.addText(empty, MARGIN + 40f, y + (emptyHeight - empty.height) / 2f)
            y = panel.bottom
        } else {
            // Adaptive columns: 2 columns for 1-2 books (with generous covers), 3 columns for 3-6 books.
            val columns = if (content.books.size <= 2) 2 else GALLERY_COLUMNS
            val gap = if (columns == 2) 44 else GALLERY_GAP
            val coverWidth = if (columns == 2) 340 else GALLERY_COVER_WIDTH
            val coverHeight = (coverWidth * 1.5f).toInt()
            val rowWidth = columns * coverWidth + (columns - 1) * gap
            val baseLeft = (WIDTH - rowWidth) / 2f
            val titleSize = if (columns == 2) 32f else 28f
            val authorSize = if (columns == 2) 26f else 24f

            val items = content.books.mapIndexed { index, book ->
                GalleryBook(
                    book = book,
                    cover = covers.getOrNull(index),
                    title = text(
                        book.title.ifBlank { context.getString(TextR.string.text_untitled) },
                        size = titleSize,
                        width = coverWidth,
                        maxLines = 2,
                        color = palette.ink,
                        weight = semibold,
                        lineSpacing = 2f,
                    ),
                    authors = book.authors.takeIf(String::isNotBlank)?.let {
                        text(it, size = authorSize, width = coverWidth, maxLines = 1, color = palette.muted, weight = regular)
                    },
                )
            }

            items.chunked(columns).forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) y += 44f
                val rowTop = y
                val actualRowWidth = row.size * coverWidth + (row.size - 1) * gap
                val rowLeft = if (rowIndex == 0 && row.size < columns) (WIDTH - actualRowWidth) / 2f else baseLeft
                val titleTop = rowTop + coverHeight + 18f
                val titleHeight = row.maxOf { it.title.height }
                val authorsHeight = row.maxOf { it.authors?.height ?: 0 }
                val authorsTop = titleTop + titleHeight + if (authorsHeight > 0) 6f else 0f
                row.forEachIndexed { column, item ->
                    val left = rowLeft + column * (coverWidth + gap)
                    operations += { canvas ->
                        drawCover(canvas, item.book, item.cover, left, rowTop, coverWidth.toFloat(), GALLERY_COVER_RADIUS, palette)
                    }
                    operations.addText(item.title, left, titleTop)
                    item.authors?.let { operations.addText(it, left, authorsTop) }
                }
                y = authorsTop + authorsHeight
            }
        }

        return Body(y, operations)
    }

    private fun addCenteredPills(
        operations: MutableList<(Canvas) -> Unit>,
        pills: List<PillItem>,
        startY: Float,
        palette: SharePalette,
    ): Float {
        val pillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = PILL_TEXT_SIZE
            typeface = semibold
            textLocales = context.resources.configuration.locales
        }
        val measured = pills.map { pill ->
            val maxTextWidth = CONTENT_WIDTH - PILL_H_PADDING * 2
            val textWidth = ceil(pillPaint.measureText(pill.label)).toInt().coerceIn(1, maxTextWidth)
            val layout = text(
                pill.label,
                size = PILL_TEXT_SIZE,
                width = textWidth,
                maxLines = 1,
                color = if (pill.emphasized) palette.ratingColor else palette.secondary,
                weight = semibold,
            )
            MeasuredPill(
                layout = layout,
                width = textWidth + PILL_H_PADDING * 2f,
                height = layout.height + PILL_V_PADDING * 2f,
                emphasized = pill.emphasized,
            )
        }

        // Wrap pills into balanced horizontal rows.
        val rows = mutableListOf<MutableList<MeasuredPill>>()
        var currentRow = mutableListOf<MeasuredPill>()
        var currentWidth = 0f
        for (pill in measured) {
            val nextWidth = if (currentRow.isEmpty()) pill.width else currentWidth + PILL_H_GAP + pill.width
            if (currentRow.isNotEmpty() && nextWidth > CONTENT_WIDTH) {
                rows += currentRow
                currentRow = mutableListOf(pill)
                currentWidth = pill.width
            } else {
                currentRow += pill
                currentWidth = nextWidth
            }
        }
        if (currentRow.isNotEmpty()) rows += currentRow

        var y = startY
        rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) y += PILL_V_GAP
            val totalWidth = row.sumOf { it.width.toDouble() }.toFloat() + PILL_H_GAP * (row.size - 1)
            val rowHeight = row.maxOf { it.height }
            var x = (WIDTH - totalWidth) / 2f
            row.forEach { pill ->
                val rect = RectF(x, y, x + pill.width, y + rowHeight)
                val radius = rowHeight / 2f
                val bgColor = palette.chipFill
                operations += { canvas ->
                    canvas.drawRoundRect(rect, radius, radius, fill(bgColor))
                }
                operations.addText(
                    pill.layout,
                    x + PILL_H_PADDING,
                    y + (rowHeight - pill.layout.height) / 2f,
                )
                x += pill.width + PILL_H_GAP
            }
            y += rowHeight
        }
        return y
    }

    private fun drawCover(
        canvas: Canvas,
        book: ShareImageBook,
        bitmap: Bitmap?,
        left: Float,
        top: Float,
        width: Float,
        radius: Float,
        palette: SharePalette,
    ) {
        val bounds = RectF(left, top, left + width, top + width * 1.5f)
        // Soft two-layer shadow for depth without a bulky card container.
        val ambientShadow = fill(palette.canvas).apply {
            setShadowLayer(28f, 0f, 14f, palette.shadowAmbient)
        }
        canvas.drawRoundRect(bounds, radius, radius, ambientShadow)
        val keyShadow = fill(palette.canvas).apply {
            setShadowLayer(12f, 0f, 5f, palette.shadowKey)
        }
        canvas.drawRoundRect(bounds, radius, radius, keyShadow)

        val save = canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(bounds, radius, radius, Path.Direction.CW) })
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
                drawClassicCover(canvas, book, bounds, palette)
            }
        } finally {
            temporary?.recycle()
            canvas.restoreToCount(save)
        }

        // Delicate hairline border so light covers stay crisp on light canvas and dark covers on dark canvas.
        canvas.drawRoundRect(bounds, radius, radius, stroke(palette.coverBorder, 1.5f))
    }

    private fun drawClassicCover(canvas: Canvas, book: ShareImageBook, bounds: RectF, palette: SharePalette) {
        val cloth = palette.classicCloths[(book.title.hashCode() ushr 1) % palette.classicCloths.size]
        canvas.drawRect(bounds, fill(cloth))
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
        val spineLeft = bounds.left + bounds.width() * 0.035f
        val spineWidth = bounds.width() * 0.025f
        canvas.drawRect(spineLeft, bounds.top, spineLeft + spineWidth, bounds.bottom, fill(palette.classicSpineShadow))
        val inset = bounds.width() * 0.075f
        val frame = RectF(bounds).apply { inset(inset, inset) }
        val frameGold = Color.argb(184, Color.red(palette.classicGold), Color.green(palette.classicGold), Color.blue(palette.classicGold))
        canvas.drawRect(frame, stroke(frameGold, max(1.2f, bounds.width() * 0.004f)))

        val insideWidth = max(1, (bounds.width() * 0.72f).toInt())
        val title = text(
            book.title.ifBlank { context.getString(TextR.string.text_untitled) },
            size = bounds.width() * 0.095f,
            width = insideWidth,
            maxLines = 4,
            color = palette.classicInk,
            weight = serifMedium,
            alignment = Layout.Alignment.ALIGN_CENTER,
            lineSpacing = 4f,
        )
        val authors = book.authors.takeIf(String::isNotBlank)?.let {
            text(
                it,
                size = bounds.width() * 0.060f,
                width = insideWidth,
                maxLines = 2,
                color = palette.classicGold,
                weight = serif,
                alignment = Layout.Alignment.ALIGN_CENTER,
            )
        }
        val ruleGap = bounds.height() * 0.036f
        val blockHeight = title.height + if (authors != null) (ruleGap * 2 + authors.height).toInt() else 0
        val textLeft = bounds.left + (bounds.width() - insideWidth) / 2f
        val titleTop = bounds.top + (bounds.height() - blockHeight) / 2f
        drawText(canvas, title, textLeft, titleTop)
        if (authors != null) {
            val ruleY = titleTop + title.height + ruleGap
            val ruleHalf = bounds.width() * 0.12f
            canvas.drawLine(bounds.centerX() - ruleHalf, ruleY, bounds.centerX() + ruleHalf, ruleY, stroke(palette.classicGold, 1.2f))
            drawText(canvas, authors, textLeft, ruleY + ruleGap)
        }
    }

    private fun drawFooter(canvas: Canvas, brand: StaticLayout, top: Float, height: Int, palette: SharePalette) {
        val ruleWidth = 120f
        val ruleY = top
        canvas.drawLine((WIDTH - ruleWidth) / 2f, ruleY, (WIDTH + ruleWidth) / 2f, ruleY, stroke(palette.divider, 1.5f))

        val rowTop = top + FOOTER_RULE_GAP
        val rowHeight = height - FOOTER_RULE_GAP
        val textWidth = (0 until brand.lineCount).maxOfOrNull(brand::getLineWidth) ?: 0f
        val rowWidth = FOOTER_LOGO_SIZE + FOOTER_LOGO_GAP + textWidth
        val left = (WIDTH - rowWidth) / 2f
        ContextCompat.getDrawable(context, R.drawable.ic_zhishi_foreground)?.mutate()?.let { logo ->
            val logoTop = rowTop + (rowHeight - FOOTER_LOGO_SIZE) / 2f
            logo.setBounds(left.toInt(), logoTop.toInt(), (left + FOOTER_LOGO_SIZE).toInt(), (logoTop + FOOTER_LOGO_SIZE).toInt())
            logo.draw(canvas)
        }
        drawText(canvas, brand, left + FOOTER_LOGO_SIZE + FOOTER_LOGO_GAP, rowTop + (rowHeight - brand.height) / 2f)
    }

    private fun text(
        value: String,
        size: Float,
        width: Int,
        maxLines: Int,
        color: Int,
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
        return StaticLayout.Builder.obtain(value, 0, value.length, paint, max(1, width))
            .setAlignment(alignment)
            .setIncludePad(false)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .setUseLineSpacingFromFallbacks(true)
            .setLineSpacing(lineSpacing, 1.08f)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setEllipsizedWidth(max(1, width))
            .build()
    }

    private fun MutableList<(Canvas) -> Unit>.addText(layout: StaticLayout, left: Float, top: Float) {
        add { canvas -> drawText(canvas, layout, left, top) }
    }

    private fun drawText(canvas: Canvas, layout: StaticLayout, left: Float, top: Float) {
        canvas.withTranslation(left, top) { layout.draw(this) }
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
    }

    private data class Body(val bottom: Float, val operations: List<(Canvas) -> Unit>) {
        fun draw(canvas: Canvas) = operations.forEach { it(canvas) }
    }

    private data class PillItem(val label: String, val emphasized: Boolean)
    private data class MeasuredPill(val layout: StaticLayout, val width: Float, val height: Float, val emphasized: Boolean)
    private data class FieldRow(val label: StaticLayout, val value: StaticLayout)
    private data class GalleryBook(val book: ShareImageBook, val cover: Bitmap?, val title: StaticLayout, val authors: StaticLayout?)

    private companion object {
        const val WIDTH = ShareImageLayout.WIDTH.toFloat()
        const val CONTENT_WIDTH = 920
        const val MARGIN = 80f
        const val TOP_SPACE = 76f
        const val BOOK_COVER_WIDTH = 380f
        const val BOOK_COVER_RADIUS = 16f
        const val GALLERY_COVER_RADIUS = 12f
        const val PANEL_PADDING = 34
        const val PANEL_RADIUS = 24f
        const val ACCENT_BAR_INSET = 22
        const val META_ROW_GAP = 24
        const val META_ITEM_SPACING = 24f
        const val PILL_TEXT_SIZE = 25f
        const val PILL_H_PADDING = 22
        const val PILL_V_PADDING = 10
        const val PILL_H_GAP = 14f
        const val PILL_V_GAP = 12f
        const val GALLERY_COLUMNS = 3
        const val GALLERY_GAP = 30
        const val GALLERY_COVER_WIDTH = 286
        // The original vector has transparent insets; this gives its visible artwork about 56 px.
        const val FOOTER_LOGO_SIZE = 88
        const val FOOTER_LOGO_GAP = 8
        const val FOOTER_RULE_GAP = 28
    }
}
