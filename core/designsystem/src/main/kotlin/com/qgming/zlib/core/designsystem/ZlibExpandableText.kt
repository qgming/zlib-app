package com.qgming.zlib.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One independently expandable document; its owner retains the expansion state outside lazy items. */
@Composable
fun ZlibExpandableText(
    title: String,
    body: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    summary: String? = null,
    markdown: Boolean = false,
    onOpenUrl: ((String) -> Unit)? = null,
) {
    val description = stringResource(if (expanded) R.string.zlib_expanded else R.string.zlib_collapsed)
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(150), label = "documentArrow")
    ZlibAboutGroup {
        BasicComponent(
            title = title,
            summary = summary,
            modifier = Modifier.heightIn(min = 56.dp).semantics { stateDescription = description },
            insideMargin = PaddingValues(horizontal = 20.dp, vertical = 7.dp),
            role = Role.Button,
            onClick = onToggle,
            endActions = {
                DropdownArrowEndAction(
                    actionColor = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    modifier = Modifier.rotate(rotation),
                )
            },
        )
        if (expanded) {
            SelectionContainer {
                if (markdown) {
                    ZlibMarkdownText(
                        markdown = body,
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
                        onOpenUrl = onOpenUrl,
                    )
                } else {
                    Text(
                        body,
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
                        style = MiuixTheme.textStyles.body2.copy(lineHeight = 26.sp),
                    )
                }
            }
        }
    }
}
