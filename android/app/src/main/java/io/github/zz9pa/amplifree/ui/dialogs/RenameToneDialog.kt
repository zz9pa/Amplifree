package io.github.zz9pa.amplifree.ui.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.zz9pa.amplifree.R
import io.github.zz9pa.amplifree.ui.components.AmpButton
import io.github.zz9pa.amplifree.ui.components.AmpOutlinedButton
import io.github.zz9pa.amplifree.ui.theme.*

/** Names the amp can hold are short and plain: printable ASCII only. */
private const val MAX_NAME = 32

private fun tidy(s: String) = s.filter { it.code in 32..126 }.take(MAX_NAME)

/**
 * Ask for a new name for the tone in [slot]. The amp has no rename command, so renaming rewrites the
 * stored tone with the new name; the dialog says so, and the last change can be undone afterwards.
 */
@Composable
fun RenameToneDialog(
    slot: Int,
    currentName: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) { RenameToneContent(slot, currentName, onRename, onDismiss) }
}

/** The dialog's content, apart from its window, so it can be shown and tested on its own. */
@Composable
internal fun RenameToneContent(
    slot: Int,
    currentName: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by rememberSaveable(slot) { mutableStateOf(currentName) }
    val name = text.trim()

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AmpSurface,
        border = BorderStroke(1.dp, AmpGold),
        modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("rename_dialog")
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.rename_title, slot),
                style = MaterialTheme.typography.titleMedium,
                color = AmpGold, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.rename_body),
                style = MaterialTheme.typography.bodySmall,
                color = AmpTextSecondary
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = tidy(it) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("rename_field")
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AmpOutlinedButton(stringResource(R.string.import_cancel), onDismiss, Modifier.weight(1f))
                AmpButton(
                    text = stringResource(R.string.rename_confirm),
                    enabled = name.isNotEmpty() && name != currentName,
                    onClick = { onRename(name) },
                    modifier = Modifier.weight(1f).testTag("rename_confirm")
                )
            }
        }
    }
}
