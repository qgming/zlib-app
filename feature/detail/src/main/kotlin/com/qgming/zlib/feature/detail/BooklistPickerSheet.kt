package com.qgming.zlib.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.designsystem.ZlibBooklistChoice
import com.qgming.zlib.core.designsystem.ZlibBooklistFormSubmit
import com.qgming.zlib.core.designsystem.ZlibBooklistNameField
import com.qgming.zlib.core.designsystem.ZlibBooklistSheetNote
import com.qgming.zlib.core.designsystem.ZlibGlassBottomSheet
import com.qgming.zlib.core.designsystem.ZlibLoading
import com.qgming.zlib.core.designsystem.ZlibMenuAction
import com.qgming.zlib.core.designsystem.ZlibNewBooklistAction
import com.qgming.zlib.core.designsystem.ZlibStateCard
import com.qgming.zlib.core.designsystem.userMessage

@Composable
internal fun BooklistPickerSheet(state: BooklistPickerState, viewModel: BooklistPickerViewModel) {
    ZlibGlassBottomSheet(
        show = state.open,
        title = stringResource(if (state.formOpen) TextR.string.text_create_booklist else TextR.string.text_add_to_booklist),
        onDismiss = viewModel::dismiss,
    ) {
        if (state.open) {
            when {
                state.request == PickerRequest.Load -> ZlibLoading()
                state.loadError != null -> ZlibStateCard(
                    title = stringResource(TextR.string.text_cannot_load, stringResource(TextR.string.text_my_booklists)),
                    message = state.loadError.userMessage(),
                    onAction = if (state.loadError == AppError.NotLoggedIn) null else viewModel::retryList,
                )
                state.formOpen -> CreateBooklistForm(state, viewModel)
                else -> BooklistChoices(state, viewModel)
            }
        }
    }
}

@Composable
private fun BooklistChoices(state: BooklistPickerState, viewModel: BooklistPickerViewModel) {
    val listState = rememberLazyListState()
    LaunchedEffect(viewModel, listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }.toSet() }
            .collect(viewModel::showPreviews)
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "new-booklist") {
            ZlibNewBooklistAction(enabled = state.request == null, onClick = viewModel::showForm)
        }
        if (state.lists.isEmpty()) {
            item(key = "empty-booklists") { ZlibBooklistSheetNote(stringResource(TextR.string.text_no_booklists)) }
        }
        items(state.lists, key = { it.booklistId }) { list ->
            ZlibBooklistChoice(
                membership = list,
                adding = list.booklistId == state.addingList,
                enabled = state.request == null,
                onClick = { viewModel.add(list.booklistId) },
            )
        }
    }
}

@Composable
private fun CreateBooklistForm(state: BooklistPickerState, viewModel: BooklistPickerViewModel) {
    val busy = state.request != null
    Column(
        Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZlibBooklistNameField(state.title, enabled = !busy && !state.needsVerification, onNameChange = viewModel::editTitle)
        state.createError?.let { ZlibBooklistSheetNote(it.userMessage(), isError = true) }
        if (state.needsVerification && !busy) {
            ZlibBooklistSheetNote(stringResource(TextR.string.text_booklist_create_unconfirmed), isError = true)
        }
        val label = when {
            state.request == PickerRequest.Create -> TextR.string.text_booklist_create_in_progress
            state.request == PickerRequest.Verify -> TextR.string.text_booklist_check_in_progress
            state.needsVerification -> TextR.string.text_booklist_recheck
            else -> TextR.string.text_create_and_add
        }
        ZlibBooklistFormSubmit(
            label = stringResource(label),
            enabled = !busy && (state.needsVerification || state.title.isNotBlank()),
            onClick = if (state.needsVerification) viewModel::recheck else viewModel::create,
        )
        ZlibMenuAction(stringResource(TextR.string.text_back_to_booklists), enabled = !busy, onClick = viewModel::backToLists)
    }
}
