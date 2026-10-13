package io.github.zz9pa.amplifree.ui.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.zz9pa.amplifree.R
import io.github.zz9pa.amplifree.ui.components.AmpButton
import io.github.zz9pa.amplifree.ui.components.AmpOutlinedButton
import io.github.zz9pa.amplifree.ui.theme.*

/**
 * Choose which stored slot to write the edited tone into, and what to call it.
 * Writing replaces whatever is in the slot, so the slot's current tone is shown and the
 * last save can be undone afterwards.
 */
@Composable
fun SaveToneDialog(
    currentName: String,
    suggestedSlot: Int?,
    slotCount: Int,
    existing: List<Pair<Int, String>>,
    onSave: (slot: Int, name: String) -> Unit,
    onDismiss: () -> Unit
) {
    var slot by rememberSaveable { mutableStateOf(suggestedSlot) }
    var name by rememberSaveable { mutableStateOf(currentName) }
    val byIndex = remember(existing) { existing.toMap() }
    val target = slot?.let { byIndex[it] }
    val emptySlot = stringResource(R.string.save_slot_empty)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = AmpSurface,
            border = BorderStroke(1.dp, AmpGold),
            modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("save_dialog")
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.save_title), style = MaterialTheme.typography.titleMedium,
                    color = AmpGold, fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(32) },
                    label = { Text(stringResource(R.string.save_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("save_name")
                )

                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.save_slot_heading), style = MaterialTheme.typography.labelMedium,
                    color = AmpGold, fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 220.dp)) {
                    items((0 until slotCount).toList()) { i ->
                        val selected = slot == i
                        Surface(
                            color = if (selected) AmpSurfaceVariant else AmpSurface,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, if (selected) AmpGold else AmpSurfaceVariant),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                .clickable(role = Role.RadioButton) { slot = i }
                                .semantics(mergeDescendants = true) { this.selected = selected }
                        ) {
                            Row(
                                Modifier.heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("%2d".format(i), color = AmpTextSecondary, fontSize = 12.sp,
                                    modifier = Modifier.width(28.dp))
                                Text(byIndex[i] ?: emptySlot, color = AmpTextPrimary,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                if (slot != null && target != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.save_replaces, target, slot!!),
                        color = AmpOrangeGlow, style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AmpOutlinedButton(stringResource(R.string.dialog_cancel), onDismiss, Modifier.weight(1f))
                    AmpButton(
                        text = stringResource(R.string.save_confirm),
                        enabled = slot != null && name.isNotBlank(),
                        onClick = { slot?.let { onSave(it, name) } },
                        modifier = Modifier.weight(1f).testTag("save_confirm")
                    )
                }
            }
        }
    }
}
