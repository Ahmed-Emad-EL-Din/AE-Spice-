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
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.clickable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComponentPropertiesDialog(
    component: Component,
    onDismiss: () -> Unit,
    onSave: (name: String, value: String, orientation: Orientation) -> Unit,
    onValueChangeInRealTime: ((String) -> Unit)? = null
) {
    var name by remember { mutableStateOf(component.name) }
    var valueStr by remember { mutableStateOf(component.valueStr) }
    var orientation by remember { mutableStateOf(component.orientation) }
    var showSpiceInput by remember { mutableStateOf(false) }
    
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

    // Helper to parse SPICE value to raw Double
    fun parseSpiceValue(str: String): Double {
        val cleaned = str.trim().lowercase()
        if (cleaned.isEmpty()) return 1.0
        val regex = """^([0-9.]+)(.*)$""".toRegex()
        val match = regex.find(cleaned) ?: return 1.0
        val numPart = match.groupValues[1].toDoubleOrNull() ?: 1.0
        val suffix = match.groupValues[2]
        return when {
            suffix.startsWith("meg") -> numPart * 1e6
            suffix.startsWith("k") -> numPart * 1e3
            suffix.startsWith("milli") || suffix.startsWith("mil") -> numPart * 1e-3
            suffix.startsWith("m") -> {
                if (suffix.startsWith("meg")) numPart * 1e6 else numPart * 1e-3
            }
            suffix.startsWith("u") -> numPart * 1e-6
            suffix.startsWith("n") -> numPart * 1e-9
            suffix.startsWith("p") -> numPart * 1e-12
            else -> numPart
        }
    }

    // Helper to format raw Double back to readable SPICE value
    fun formatSpiceValue(value: Double): String {
        return when {
            value >= 1e6 -> String.format("%.1f", value / 1e6) + "meg"
            value >= 1e3 -> String.format("%.1f", value / 1e3) + "k"
            value >= 1.0 -> String.format("%.1f", value)
            value >= 1e-3 -> String.format("%.1f", value * 1e3) + "m"
            value >= 1e-6 -> String.format("%.1f", value * 1e6) + "u"
            value >= 1e-9 -> String.format("%.1f", value * 1e9) + "n"
            else -> String.format("%.1f", value * 1e12) + "p"
        }
    }

    data class UnitOption(val label: String, val suffix: String)

    val resistorUnits = listOf(
        UnitOption("Ω", ""),
        UnitOption("kΩ", "k"),
        UnitOption("MΩ", "meg"),
        UnitOption("mΩ", "m")
    )

    val capacitorUnits = listOf(
        UnitOption("pF", "p"),
        UnitOption("nF", "n"),
        UnitOption("uF", "u"),
        UnitOption("mF", "m"),
        UnitOption("F", "")
    )

    val inductorUnits = listOf(
        UnitOption("uH", "u"),
        UnitOption("mH", "m"),
        UnitOption("H", "")
    )

    val voltageUnits = listOf(
        UnitOption("V", ""),
        UnitOption("mV", "m"),
        UnitOption("kV", "k")
    )

    val currentUnits = listOf(
        UnitOption("uA", "u"),
        UnitOption("mA", "m"),
        UnitOption("A", "")
    )

    fun getUnitOptionsFor(type: ComponentType): List<UnitOption> {
        return when (type) {
            ComponentType.RESISTOR -> resistorUnits
            ComponentType.CAPACITOR -> capacitorUnits
            ComponentType.INDUCTOR -> inductorUnits
            ComponentType.VOLTAGE_SOURCE -> voltageUnits
            ComponentType.CURRENT_SOURCE -> currentUnits
            else -> emptyList()
        }
    }

    val unitOptions = remember(component.type) { getUnitOptionsFor(component.type) }

    val initialParsed = remember(component.valueStr) {
        val rawStr = component.valueStr.trim()
        val regex = """^([0-9.]+)(.*)$""".toRegex()
        val match = regex.find(rawStr)
        if (match != null) {
            val numPart = match.groupValues[1].toDoubleOrNull() ?: 10.0
            val suffixPart = match.groupValues[2].trim().lowercase()
            
            val bestUnit = unitOptions.find { option ->
                val cleanOptionSuffix = option.suffix.lowercase()
                if (cleanOptionSuffix.isEmpty()) {
                    false
                } else {
                    suffixPart.startsWith(cleanOptionSuffix) || cleanOptionSuffix.startsWith(suffixPart)
                }
            } ?: unitOptions.find { it.suffix.isEmpty() } ?: unitOptions.firstOrNull() ?: UnitOption("", "")
            
            Pair(numPart.coerceIn(0.99, 999.99), bestUnit)
        } else {
            Pair(10.0, unitOptions.firstOrNull() ?: UnitOption("", ""))
        }
    }

    var numericValue by remember {
        mutableStateOf(initialParsed.first)
    }
    var selectedUnit by remember {
        mutableStateOf(initialParsed.second)
    }

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

                val isMainComponent = component.type in setOf(
                    ComponentType.RESISTOR,
                    ComponentType.CAPACITOR,
                    ComponentType.INDUCTOR,
                    ComponentType.VOLTAGE_SOURCE,
                    ComponentType.CURRENT_SOURCE
                )

                if (!isMainComponent) {
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
                    HorizontalDivider()
                }

                // If the component type has unit choices, configure the Slider first, then numeric value/unit below it, then SPICE notation optionally.
                val hasSlider = unitOptions.isNotEmpty() && (component.type != ComponentType.VOLTAGE_SOURCE || (!valueStr.contains("SINE", true) && !valueStr.contains("PULSE", true)))

                if (hasSlider) {
                    // 1. Slider First
                    Text(
                        text = "Slider Value",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Slider(
                        value = numericValue.toFloat(),
                        onValueChange = { newVal ->
                            numericValue = newVal.toDouble().coerceIn(0.99, 999.99)
                            val formattedStr = String.format(java.util.Locale.US, "%.2f", numericValue) + selectedUnit.suffix
                            valueStr = formattedStr
                            onValueChangeInRealTime?.invoke(formattedStr)
                        },
                        valueRange = 0.99f..999.99f,
                        modifier = Modifier.fillMaxWidth().testTag("dialog_value_slider")
                    )

                    // 2. Value Input & Unit Dropdown below it
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = String.format(java.util.Locale.US, "%.2f", numericValue),
                            onValueChange = { inputVal ->
                                val parsedNum = inputVal.toDoubleOrNull()
                                if (parsedNum != null) {
                                    numericValue = parsedNum.coerceIn(0.99, 999.99)
                                    val formattedStr = String.format(java.util.Locale.US, "%.2f", numericValue) + selectedUnit.suffix
                                    valueStr = formattedStr
                                    onValueChangeInRealTime?.invoke(formattedStr)
                                }
                            },
                            label = { Text("Value") },
                            singleLine = true,
                            modifier = Modifier.weight(1.5f).testTag("dialog_numeric_value_input")
                        )

                        var menuExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = menuExpanded,
                            onExpandedChange = { menuExpanded = !menuExpanded },
                            modifier = Modifier.weight(1f)
                        ) {
                            OutlinedTextField(
                                value = selectedUnit.label,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Unit") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                modifier = Modifier
                                    .menuAnchor()
                                    .testTag("dialog_unit_dropdown_field")
                            )

                            ExposedDropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                unitOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        onClick = {
                                            selectedUnit = option
                                            menuExpanded = false
                                            val formattedStr = String.format(java.util.Locale.US, "%.2f", numericValue) + option.suffix
                                            valueStr = formattedStr
                                            onValueChangeInRealTime?.invoke(formattedStr)
                                        },
                                        modifier = Modifier.testTag("dialog_dropdown_item_${option.suffix}")
                                    )
                                }
                            }
                        }
                    }

                    // 3. SPICE notation optional toggle and input field
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = showSpiceInput,
                            onCheckedChange = { showSpiceInput = it },
                            modifier = Modifier.testTag("spice_notation_checkbox")
                        )
                        Text("Edit SPICE string manually", style = MaterialTheme.typography.bodyMedium)
                    }

                    if (showSpiceInput) {
                        OutlinedTextField(
                            value = valueStr,
                            onValueChange = { newValue ->
                                valueStr = newValue
                                onValueChangeInRealTime?.invoke(newValue)
                                
                                // Try to sync manual typing back into the numeric slider and dropdown unit
                                val rawStr = newValue.trim()
                                val regex = """^([0-9.]+)(.*)$""".toRegex()
                                val match = regex.find(rawStr)
                                if (match != null) {
                                    val numPart = match.groupValues[1].toDoubleOrNull()
                                    val suffixPart = match.groupValues[2].trim().lowercase()
                                    
                                    val bestUnit = unitOptions.find { option ->
                                        val cleanOptionSuffix = option.suffix.lowercase()
                                        cleanOptionSuffix.isNotEmpty() && (suffixPart.startsWith(cleanOptionSuffix) || cleanOptionSuffix.startsWith(suffixPart))
                                    } ?: unitOptions.find { it.suffix.isEmpty() }
                                    
                                    if (bestUnit != null && bestUnit != selectedUnit) {
                                        selectedUnit = bestUnit
                                    }
                                    if (numPart != null) {
                                        val clamped = numPart.coerceIn(0.99, 999.99)
                                        if (Math.abs(clamped - numericValue) > 0.01) {
                                            numericValue = clamped
                                        }
                                    }
                                }
                            },
                            label = { Text("SPICE Notation") },
                            placeholder = { Text("e.g. 1k, 10u") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("dialog_spice_value_input")
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = valueStr,
                        onValueChange = { newValue ->
                            valueStr = newValue
                            onValueChangeInRealTime?.invoke(newValue)
                        },
                        label = { Text("Value / Spec") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("dialog_spice_value_input")
                    )
                }

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
