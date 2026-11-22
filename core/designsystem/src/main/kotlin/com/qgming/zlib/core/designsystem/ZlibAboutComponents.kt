package com.qgming.zlib.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ZlibAboutHeader(name: String, version: String, icon: Painter) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(icon, contentDescription = null, modifier = Modifier.size(152.dp))
        Text(
            name, modifier = Modifier.padding(top = 24.dp),
            style = MiuixTheme.textStyles.title1.copy(fontSize = 28.sp, fontWeight = FontWeight.Medium),
        )
        Text(
            version, modifier = Modifier.padding(top = 6.dp),
            style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
fun ZlibAboutGroup(content: @Composable ColumnScope.() -> Unit) {
    ZlibCard(insideMargin = PaddingValues(0.dp), cornerRadius = 24.dp, content = content)
}

@Composable
fun ZlibAboutLink(title: String, onClick: () -> Unit) {
    ArrowPreference(
        title = title,
        insideMargin = PaddingValues(horizontal = 20.dp, vertical = 7.dp),
        onClick = onClick,
    )
}

@Composable
fun ZlibAboutText(title: String, body: String) {
    SelectionContainer {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MiuixTheme.textStyles.title3)
            Text(body, style = MiuixTheme.textStyles.body2.copy(lineHeight = 26.sp))
        }
    }
}

@Composable
fun ZlibUpdateActions(cancel: String, action: String?, enabled: Boolean, onCancel: () -> Unit, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(cancel) }
        if (action != null) Button(onClick = onAction, enabled = enabled, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) {
            Text(action)
        }
    }
}

@Composable
fun ZlibUpdateProgress(written: Long, total: Long, status: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (total > 0) LinearProgressIndicator(progress = { (written.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        else ZlibLoadingIndicator()
        Text(status, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}
