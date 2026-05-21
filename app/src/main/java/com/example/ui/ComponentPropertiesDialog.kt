package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.Component
import com.example.engine.ComponentType
import com.example.engine.Orientation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComponentPropertiesDialog(
    component: Component,
    onDismiss: () -> Unit,
    onSave: (name: String, value: String, orientation: Orientation) -> Unit
) {
    var name by remember { mutableStateOf(component.name) }
    var valueStr by remember { mutableStateOf(component.valueStr) }
    var orientation by remember { mutableStateOf(component.orientation) }
    
    // Auxiliary states for Guided waveform generator helpers
    var isACGuideOpen by remember { mutableStateOf(false) }
    var isPulseGuideOpen by remember { mutableStateOf(false) }

    // SINE helper parameters
    var sineOffset by remember { mutableStateOf("0") }
    var sineAmp by remember { mutableStateOf("10") }
    var sineFreq by remember { mutableStateOf("1k") }
    
    // PULSE helper parameters
    var pulseV1 by remember { mutableStateOf("0") }
    var pulseV2 by remember { mutableStateOf("5") }
    var pulseDelay by remember { mutableStateOf("0") }
    var pulseRise by remember { mutableStateOf("1u") }
    var pulseFall by remember { mutableStateOf("1u") }
    var pulseWidth by remember { mutableStateOf("5m") }
    var pulsePeriod by remember { mutableStateOf("10m") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "${component.type.name} Attributes",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Component Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Component ID") },
                    placeholder = { Text("e.g. R1, V1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Orientation Rotator Selector
                Text(
                    text = "Orientation (Rotation)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Orientation.values().forEach { orient ->
                        Button(
                            onClick = { orientation = orient },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (orientation == orient) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (orientation == orient) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                        ) {
                            Text("${orient.degrees.toInt()}°", fontSize = 12.sp)
                        }
                    }
                }

                Divider()

                // Component Value
                OutlinedTextField(
                    value = valueStr,
                    onValueChange = { valueStr = it },
                    label = { Text("Component Value (SPICE notation)") },
                    placeholder = { Text("e.g. 1k, 10u, 5") },
                    singleLine = true,
                    supportingText = {
                        Text("Use suffixes: k=kilo, m=milli, meg=Mega, u=micro, n=nano, p=pico")
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // If voltage source, show Sine/Pulse helpers!
                if (component.type == ComponentType.VOLTAGE_SOURCE) {
                    Text(
                        text = "Source Function Builders:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                isACGuideOpen = true
                                isPulseGuideOpen = false
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("SINE Generator", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                isPulseGuideOpen = true
                                isACGuideOpen = false
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("PULSE Generator", fontSize = 11.sp)
                        }
                    }

                    // Sine Guided Panel
                    if (isACGuideOpen) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Sine Wave Parameters", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = sineOffset,
                                        onValueChange = { sineOffset = it },
                                        label = { Text("DC Offset", fontSize = 10.sp) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = sineAmp,
                                        onValueChange = { sineAmp = it },
                                        label = { Text("Amplitude", fontSize = 10.sp) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = sineFreq,
                                        onValueChange = { sineFreq = it },
                                        label = { Text("Freq (Hz)", fontSize = 10.sp) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Button(
                                    onClick = {
                                        valueStr = "SINE($sineOffset $sineAmp $sineFreq)"
                                        isACGuideOpen = false
                                    },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("Apply Sine", fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    // Pulse Guided Panel
                    if (isPulseGuideOpen) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Pulse Signal Parameters", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedTextField(
                                        value = pulseV1,
                                        onValueChange = { pulseV1 = it },
                                        label = { Text("V1 (Low)", fontSize = 9.sp) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = pulseV2,
                                        onValueChange = { pulseV2 = it },
                                        label = { Text("V2 (High)", fontSize = 9.sp) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = pulseWidth,
                                        onValueChange = { pulseWidth = it },
                                        label = { Text("Width", fontSize = 9.sp) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    // Period
                                    OutlinedTextField(
                                        value = pulsePeriod,
                                        onValueChange = { pulsePeriod = it },
                                        label = { Text("Period", fontSize = 9.sp) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Button(
                                    onClick = {
                                        valueStr = "PULSE($pulseV1 $pulseV2 $pulseDelay $pulseRise $pulseFall $pulseWidth $pulsePeriod)"
                                        isPulseGuideOpen = false
                                    },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("Apply Pulse", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, valueStr, orientation) }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
