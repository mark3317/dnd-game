package ru.markn.dnd.adventure.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** Platform focus/dismissal, with the same frame and actions as the game panels. */
@Composable
internal fun AdventureDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        AdventureDialogContent(title, text, confirmButton, dismissButton)
    }
}

@Composable
internal fun AdventureDialogContent(
    title: String,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    BoxWithConstraints(Modifier.widthIn(max = 560.dp).fillMaxWidth().imePadding()) {
        AdventurePanel(Modifier.fillMaxWidth().heightIn(max = maxHeight * .9f)
            .semantics { paneTitle = title }, emphasized = true) {
            AdventureSectionTitle(title, Modifier.semantics { heading() })
            AdventureDivider()
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())) {
                text()
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}
