package io.github.zz9pa.amplifree.ui.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
    var slot by remember { mutableStateOf(suggestedSlot) }
    var name by remember { mutableStateOf(currentName) }
    val byIndex = remember(existing) { existing.toMap() }
    val target = slot?.let { byIndex[it] }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = AmpSurface,
            border = BorderStroke(1.dp, AmpGold),
            modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("save_dialog")
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("SAVE TONE TO AMP", style = MaterialTheme.typography.titleMedium,
                    color = AmpGold, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(32) },
                    label = { Text("Tone name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("save_name")
                )

                Spacer(Modifier.height(12.dp))
                Text("SLOT", style = MaterialTheme.typography.labelSmall,
                    color = AmpGold, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.heightIn(max = 220.dp)) {
                    items((0 until slotCount).toList()) { i ->
                        val selected = slot == i
                        Surface(
                            color = if (selected) AmpSurfaceVariant else AmpSurface,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, if (selected) AmpGold else AmpSurfaceVariant),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                .clickable { slot = i }
                        ) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Text("%2d".format(i), color = AmpTextSecondary, fontSize = 12.sp,
                                    modifier = Modifier.width(28.dp))
                                Text(byIndex[i] ?: "(empty)", color = AmpTextPrimary, fontSize = 13.sp)
                            }
                        }
                    }
                }

                if (slot != null && target != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This replaces \"$target\" in slot $slot. You can undo it straight after.",
                        color = AmpOrangeGlow, fontSize = 12.sp
                    )
                }

                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    OutlinedButton(onClick = onDismiss, border = BorderStroke(1.dp, AmpGold)) {
                        Text("CANCEL", color = AmpGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        enabled = slot != null && name.isNotBlank(),
                        onClick = { slot?.let { onSave(it, name) } },
                        colors = ButtonDefaults.buttonColors(containerColor = AmpGold, contentColor = AmpMetallicDark),
                        modifier = Modifier.testTag("save_confirm")
                    ) { Text("SAVE", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
