package com.qgming.zlib.core.designsystem

import androidx.compose.ui.res.stringResource
import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import top.yukonga.miuix.kmp.basic.Text

/** Real covers replace the local typeset cover only once the image has loaded. */
@Composable
fun ZlibCover(
    url: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    title: String = "",
    authors: String = "",
) {
    var imageLoaded by remember(url) { mutableStateOf(false) }
    Box(
        modifier = modifier.clip(RoundedCornerShape(8.dp)).semantics {
            if (contentDescription != null) this.contentDescription = contentDescription
        },
    ) {
        if (!imageLoaded) ClassicBookCover(title, authors, Modifier.matchParentSize())
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onLoading = { imageLoaded = false },
                onSuccess = { imageLoaded = true },
                onError = { imageLoaded = false },
            )
        }
    }
}

private val CoverCloths = listOf(
    Color(0xFF293F3C),
    Color(0xFF2C3A50),
    Color(0xFF58383B),
    Color(0xFF4C4436),
)
private val CoverInk = Color(0xFFF4EBD8)
private val CoverGold = Color(0xFFC3AA77)

@Composable
private fun ClassicBookCover(title: String, authors: String, modifier: Modifier) {
    val cloth = CoverCloths[(title.hashCode() ushr 1) % CoverCloths.size]
    BoxWithConstraints(
        modifier = modifier
            .background(cloth)
            .clearAndSetSemantics { }
            .drawBehind {
                val inset = size.width * 0.075f
                val stroke = (size.width * 0.004f).coerceAtLeast(0.5.dp.toPx())
                drawRect(
                    color = CoverGold.copy(alpha = 0.72f),
                    topLeft = Offset(inset, inset),
                    size = Size((size.width - inset * 2).coerceAtLeast(0f), (size.height - inset * 2).coerceAtLeast(0f)),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
                )
                drawLine(
                    color = Color.Black.copy(alpha = 0.18f),
                    start = Offset(size.width * 0.035f, 0f),
                    end = Offset(size.width * 0.035f, size.height),
                    strokeWidth = size.width * 0.025f,
                )
            },
    ) {
        val titleSize = (maxWidth.value * 0.145f).coerceIn(9f, 25f)
        val authorSize = (maxWidth.value * 0.082f).coerceIn(6f, 13f)
        val horizontalInset = maxWidth * 0.14f
        val verticalInset = maxHeight * 0.13f
        val ruleTopGap = maxHeight * 0.035f
        val ruleBottomGap = maxHeight * 0.045f
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = horizontalInset, vertical = verticalInset),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = title.trim().ifBlank { stringResource(TextR.string.text_untitled) },
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Medium,
                    fontSize = titleSize.sp,
                    lineHeight = (titleSize * 1.35f).sp,
                    color = CoverInk,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(ruleTopGap))
            Spacer(Modifier.fillMaxWidth(0.3f).height(0.5.dp).background(CoverGold))
            Spacer(Modifier.height(ruleBottomGap))
            Text(
                text = authors.trim().ifBlank { stringResource(TextR.string.text_unknown_author) },
                fontFamily = FontFamily.Serif,
                fontSize = authorSize.sp,
                lineHeight = (authorSize * 1.4f).sp,
                color = CoverInk.copy(alpha = 0.86f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
