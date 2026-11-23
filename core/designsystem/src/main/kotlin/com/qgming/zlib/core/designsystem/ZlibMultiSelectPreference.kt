package com.qgming.zlib.core.designsystem

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassDropdownPopup
import top.yukonga.miuix.kmp.glass.GlassMotion
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.glass.GlassPopupSizing
import top.yukonga.miuix.kmp.glass.glassPopupAnchorValue
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.PopupLayout

data class ZlibSelectionOption(val key: String, val label: String, val searchText: String = label)

/** Settings-style glass dropdown that keeps every check independent and stays open after a pick. */
@Composable
fun ZlibMultiSelectPreference(
    title: String,
    allLabel: String,
    options: List<ZlibSelectionOption>,
    selected: Set<String>,
    searchable: Boolean = false,
    onChange: (Set<String>) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val mounted = remember { mutableStateOf(false) }
    // The public popup has no exit-finished callback. Follow its geometry spring so the outer
    // PopupLayout can release its full-screen input blocker after the panel has closed.
    val hostProgress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = GlassMotion.arcBounds<Float>(expanded).let {
            spring(dampingRatio = it.dampingRatio, stiffness = it.stiffness, visibilityThreshold = 0.0001f)
        },
        finishedListener = { if (!expanded && it == 0f) mounted.value = false },
        label = "multiSelectPopupHost",
    )
    LaunchedEffect(expanded, hostProgress) {
        if (!expanded && hostProgress == 0f) mounted.value = false
    }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var query by remember { mutableStateOf("") }
    val anchor = rememberGlassPopupAnchor()
    val backdrop = LocalZlibOverlayBackdrop.current
    val actionColor = MiuixTheme.colorScheme.onSurfaceVariantActions
    val value = when (selected.size) {
        0 -> allLabel
        1 -> options.firstOrNull { it.key in selected }?.label ?: "已选 1 项"
        else -> "已选 ${selected.size} 项"
    }
    BasicComponent(
        title = title,
        insideMargin = PaddingValues(horizontal = 20.dp, vertical = 7.dp),
        role = Role.DropdownList,
        onClick = { query = ""; mounted.value = true; expanded = !expanded },
        endActions = {
            Row(
                Modifier.widthIn(max = 220.dp).glassPopupAnchorValue(anchor)
                    .onGloballyPositioned { bounds = it.boundsInWindow() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(value, modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                    fontSize = MiuixTheme.textStyles.body2.fontSize, color = actionColor,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                DropdownArrowEndAction(actionColor = actionColor)
            }
        },
    )
    // Retain the panel through its exit, then unmount the host so other rows receive input.
    if (!mounted.value) return
    PopupLayout(visible = mounted, enableWindowDim = false, enableBackHandler = false,
        enterTransition = EnterTransition.None, exitTransition = ExitTransition.None) {
        Box(Modifier.fillMaxSize()) {
            GlassDropdownPopup(
                show = expanded, onDismissRequest = { expanded = false }, anchorBounds = bounds,
                anchor = anchor, backdrop = backdrop,
                sizing = GlassPopupSizing(minWidth = 280.dp, maxWidth = 360.dp, maxHeight = 440.dp),
                visuals = GlassPopupDefaults.visuals(style = GlassDefaults.style(zlibIsDark()),
                    stroke = if (backdrop != null) zlibGlassStroke() else null,
                    containerColor = MiuixTheme.colorScheme.surfaceContainer),
            ) {
                if (searchable) TextField(
                    value = query, onValueChange = { query = it }, label = "查找语言", singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                )
                GlassPopupItem(text = allLabel, selected = selected.isEmpty(), onClick = { onChange(emptySet()) }, modifier = Modifier.heightIn(min = 48.dp))
                val matching = options.filter { query.isBlank() || it.searchText.contains(query, ignoreCase = true) }
                matching.forEach { option ->
                    GlassPopupItem(text = option.label, selected = option.key in selected, modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { onChange(if (option.key in selected) selected - option.key else selected + option.key) })
                }
                if (matching.isEmpty()) Text("没有匹配的语言", modifier = Modifier.padding(20.dp), style = MiuixTheme.textStyles.body2)
                GlassPopupItem(text = "完成", onClick = { expanded = false }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }
}
