package io.github.zz9pa.amplifree.ui.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.zz9pa.amplifree.R
import io.github.zz9pa.amplifree.ui.ModelNames
import io.github.zz9pa.amplifree.ui.components.AmpOutlinedButton
import io.github.zz9pa.amplifree.ui.theme.*

/**
 * Choose which model a block (amp, cab, pedal) should hold. The choices are the models this
 * amp's own stored tones use, so they are known to exist on this amp.
 */
@Composable
fun ModelPickerDialog(
    title: String,
    current: String?,
    options: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(options, query) { options.filter { ModelNames.matches(it, query) } }
    val pickAction = stringResource(R.string.picker_choose_action)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = AmpSurface,
            border = BorderStroke(1.dp, AmpGold),
            modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("model_picker")
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.picker_title, title.uppercase()), style = MaterialTheme.typography.titleMedium,
                    color = AmpGold, fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.picker_hint),
                    color = AmpTextSecondary, style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.picker_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("model_search")
                )
                Spacer(Modifier.height(8.dp))
                if (shown.isEmpty()) {
                    Text(stringResource(R.string.picker_no_match, query), color = AmpTextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 12.dp))
                }
                // Takes the space the other parts leave, so the dialog still fits in landscape.
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 340.dp)) {
                    items(shown) { m ->
                        val isCurrent = m == current
                        Surface(
                            color = if (isCurrent) AmpSurfaceVariant else AmpSurface,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, if (isCurrent) AmpGold else AmpSurfaceVariant),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                .clickable(onClickLabel = pickAction, role = Role.Button) { onPick(m) }
                                .semantics(mergeDescendants = true) { selected = isCurrent }
                        ) {
                            Row(
                                Modifier
                                    .heightIn(min = 48.dp)
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(ModelNames.name(m), color = AmpTextPrimary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f))
                                if (isCurrent) {
                                    Icon(Icons.Default.Check,
                                        contentDescription = stringResource(R.string.picker_current), tint = AmpGold)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                AmpOutlinedButton(stringResource(R.string.dialog_cancel), onDismiss)
            }
        }
    }
}
