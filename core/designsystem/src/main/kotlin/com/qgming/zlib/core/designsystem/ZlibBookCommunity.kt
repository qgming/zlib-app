package com.qgming.zlib.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.model.BooklistMembership
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Add
import top.yukonga.miuix.kmp.icon.glass.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ZlibBooklistChoice(membership: BooklistMembership, adding: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val addingLabel = stringResource(TextR.string.text_booklist_add_in_progress)
    ZlibBooklistCard(
        booklist = membership.booklist,
        modifier = if (adding) Modifier.semantics { stateDescription = addingLabel } else Modifier,
        onClick = onClick.takeIf { enabled && !membership.isMember && !adding },
        trailing = {
            if (adding) {
                ZlibLoadingIndicator(Modifier.size(24.dp))
            } else if (membership.isMember) {
                val tint = MiuixTheme.colorScheme.primary
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(MiuixIcons.Glass.Normal.Ok, contentDescription = null, modifier = Modifier.size(18.dp), tint = tint)
                    Text(stringResource(TextR.string.text_booklist_already_contains_book), style = MiuixTheme.textStyles.footnote1, color = tint)
                }
            }
        },
    )
}

@Composable
fun ZlibNewBooklistAction(enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(MiuixIcons.Glass.Normal.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(TextR.string.text_create_booklist))
        }
    }
}

@Composable
fun ZlibBooklistNameField(name: String, enabled: Boolean, onNameChange: (String) -> Unit) {
    TextField(
        value = name,
        onValueChange = onNameChange,
        enabled = enabled,
        label = stringResource(TextR.string.text_booklist_name),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun ZlibBooklistSheetNote(message: String, isError: Boolean = false) {
    Text(
        message,
        modifier = Modifier.padding(horizontal = 4.dp),
        style = MiuixTheme.textStyles.footnote1,
        color = if (isError) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
fun ZlibBooklistFormSubmit(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColorsPrimary(),
    ) { Text(label, textAlign = TextAlign.Center) }
}
