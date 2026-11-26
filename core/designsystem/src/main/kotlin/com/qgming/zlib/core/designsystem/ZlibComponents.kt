package com.qgming.zlib.core.designsystem

import androidx.compose.ui.res.stringResource
import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassCard
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ZlibCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    insideMargin: PaddingValues = PaddingValues(16.dp),
    cornerRadius: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalZlibGlass.current) {
        if (onClick != null) {
            GlassCard(
                modifier = modifier.fillMaxWidth(),
                cornerRadius = cornerRadius,
                insideMargin = insideMargin,
                showIndication = true,
                onClick = onClick,
                content = content,
            )
        } else {
            GlassCard(
                modifier = modifier.fillMaxWidth(),
                cornerRadius = cornerRadius,
                insideMargin = insideMargin,
                content = content,
            )
        }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            insideMargin = insideMargin,
            cornerRadius = cornerRadius,
            onClick = onClick,
            showIndication = onClick != null,
            content = content,
        )
    }
}

@Composable
fun ZlibTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onDone: (() -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    outputTransformation: OutputTransformation? = null,
) {
    val state = rememberTextFieldState(initialText = value)
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnDone by rememberUpdatedState(onDone)

    LaunchedEffect(value) {
        if (state.text.toString() != value) {
            state.setTextAndPlaceCursorAtEnd(value)
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }
            .distinctUntilChanged()
            .collect { text ->
                if (text != latestValue) latestOnValueChange(text)
            }
    }

    val lineLimits = if (singleLine) {
        TextFieldLineLimits.SingleLine
    } else {
        TextFieldLineLimits.MultiLine(
            minHeightInLines = minLines.coerceAtLeast(1),
            maxHeightInLines = maxLines.coerceAtLeast(minLines.coerceAtLeast(1)),
        )
    }
    val actionHandler = onDone?.let {
        KeyboardActionHandler { latestOnDone?.invoke() }
    }

    if (LocalZlibGlass.current) {
        GlassTextField(
            state = state,
            modifier = modifier,
            label = label,
            enabled = enabled,
            keyboardOptions = keyboardOptions,
            onKeyboardAction = actionHandler,
            lineLimits = lineLimits,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            outputTransformation = outputTransformation,
        )
    } else {
        TextField(
            state = state,
            modifier = modifier,
            label = label,
            enabled = enabled,
            keyboardOptions = keyboardOptions,
            onKeyboardAction = actionHandler,
            lineLimits = lineLimits,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            outputTransformation = outputTransformation,
        )
    }
}

@Composable
fun ZlibSectionTitle(text: String, modifier: Modifier = Modifier) {
    SmallTitle(
        text = text,
        modifier = modifier,
        textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        insideMargin = PaddingValues(start = 4.dp, top = 6.dp, bottom = 2.dp),
    )
}

@Composable
fun ZlibLoading() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        ZlibLoadingIndicator()
    }
}
@Composable
fun ZlibLoadingIndicator(modifier: Modifier = Modifier) {
    InfiniteProgressIndicator(
        modifier = modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
    )
}

@Composable
fun ZlibLoadingStatus(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZlibLoadingIndicator()
        Text(text, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
fun ZlibStateCard(
    title: String,
    message: String,
    actionLabel: String = stringResource(TextR.string.text_retry),
    onAction: (() -> Unit)? = null,
) {
    ZlibCard {
        Text(title, style = MiuixTheme.textStyles.title4)
        Text(
            text = message,
            modifier = Modifier.padding(top = 4.dp),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        if (onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 12.dp)) {
                Text(actionLabel)
            }
        }
    }
}
