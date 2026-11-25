package com.qgming.zlib.share

import kotlin.math.ceil
import kotlin.math.min

/** Keeps a measured body and an unscaled brand row within the exported bitmap. */
internal data class ShareImagePageLayout(
    val height: Int,
    val bodyScale: Float,
    val bodyLeft: Float,
    val footerTop: Int,
)

internal object ShareImageLayout {
    const val WIDTH = 1_080
    const val MAX_HEIGHT = 3_000
    const val FOOTER_GAP = 48
    const val BOTTOM_SPACE = 64

    fun page(bodyBottom: Float, footerHeight: Int): ShareImagePageLayout {
        require(bodyBottom.isFinite() && bodyBottom >= 0f)
        require(footerHeight in 1 until MAX_HEIGHT - FOOTER_GAP - BOTTOM_SPACE)
        val availableBodyHeight = MAX_HEIGHT - FOOTER_GAP - footerHeight - BOTTOM_SPACE
        val scale = if (bodyBottom == 0f) 1.0 else min(1.0, availableBodyHeight / bodyBottom.toDouble())
        val bodyHeight = ceil(bodyBottom * scale).toInt().coerceAtMost(availableBodyHeight)
        val footerTop = bodyHeight + FOOTER_GAP
        return ShareImagePageLayout(
            height = footerTop + footerHeight + BOTTOM_SPACE,
            bodyScale = scale.toFloat(),
            bodyLeft = (WIDTH * (1.0 - scale) / 2.0).toFloat(),
            footerTop = footerTop,
        )
    }
}
