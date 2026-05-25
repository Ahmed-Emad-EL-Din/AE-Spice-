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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext

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

    fun rebuildSpiceString(
        type: ComponentType,
        model: String,
        isVal: String,
        vtVal: String,
        nVal: String,
        bfVal: String,
        vtoVal: String,
        kpVal: String,
        vtrigVal: String,
        vholdVal: String,
        rgateVal: String,
        ronVal: String,
        roffVal: String,
        rcoilVal: String,
        gainVal: String,
        routVal: String,
        vmaxVal: String
    ): String {
        val modelPrefix = model.trim().ifEmpty { "MODEL" }
        return when (type) {
            ComponentType.DIODE -> "$modelPrefix is=$isVal vt=$vtVal n=$nVal"
            ComponentType.TRANSISTOR_NPN -> "$modelPrefix is=$isVal vt=$vtVal bf=$bfVal"
            ComponentType.MOSFET_N -> "$modelPrefix vto=$vtoVal kp=$kpVal"
            ComponentType.THYRISTOR -> "$modelPrefix vtrigger=$vtrigVal vholding=$vholdVal rgate=$rgateVal ron=$ronVal roff=$roffVal"
            ComponentType.TRIAC -> "$modelPrefix vtrigger=$vtrigVal vholding=$vholdVal rgate=$rgateVal ron=$ronVal roff=$roffVal"
            ComponentType.RELAY -> "$modelPrefix vtrigger=$vtrigVal rcoil=$rcoilVal ron=$ronVal roff=$roffVal"
            ComponentType.OPAMP -> "$modelPrefix gain=$gainVal rout=$routVal vmax=$vmaxVal"
            else -> valueStr
        }
    }
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

    // Helper to parse complex semiconductor and active device models (handles multi-line SPICE model cards and continuation)
    fun parseModelParams(str: String): Pair<String, Map<String, String>> {
        val lines = str.lines()
        val mergedLines = mutableListOf<String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("*") || trimmed.startsWith(";")) continue
            if (trimmed.startsWith("+")) {
                if (mergedLines.isNotEmpty()) {
                    val lastIdx = mergedLines.size - 1
                    mergedLines[lastIdx] = mergedLines[lastIdx] + " " + trimmed.substring(1).trim()
                } else {
                    mergedLines.add(trimmed.substring(1).trim())
                }
            } else {
                mergedLines.add(trimmed)
            }
        }

        var modelName = ""
        var parameterString = ""

        for (line in mergedLines) {
            val trimmedLine = line.trim()
            if (trimmedLine.lowercase().startsWith(".model ")) {
                val body = trimmedLine.substring(7).trim()
                val tokens = body.split("\\s+".toRegex())
                if (tokens.size >= 2) {
                    modelName = tokens[0]
                    parameterString = tokens.drop(2).joinToString(" ").replace("(", " ").replace(")", " ")
                    break
                }
            }
        }

        if (modelName.isEmpty() && mergedLines.isNotEmpty()) {
            val combined = mergedLines.joinToString(" ").replace("(", " ").replace(")", " ")
            val words = combined.split("\\s+".toRegex())
            val firstWord = words.firstOrNull { it.isNotEmpty() && !it.contains("=") && !it.startsWith(".") }
            modelName = firstWord ?: ""
            parameterString = combined
        }

        val paramsMap = mutableMapOf<String, String>()
        val regex = """([a-zA-Z0-9_]+)\s*=\s*([+\-a-zA-Z0-9_.eEμuμkpnmfgt/]+)""".toRegex()
        regex.findAll(parameterString).forEach { match ->
            val key = match.groupValues[1].lowercase()
            val value = match.groupValues[2]
            paramsMap[key] = value
        }
        return Pair(modelName, paramsMap)
    }

    val parsedInitialModel = remember(component.id) {
        val (modelName, paramsMap) = parseModelParams(component.valueStr)
        val defaultModel = when(component.type) {
            ComponentType.DIODE -> "1N4148"
            ComponentType.TRANSISTOR_NPN -> "Q2N2222"
            ComponentType.MOSFET_N -> "M2N7000"
            ComponentType.THYRISTOR -> "X1"
            ComponentType.TRIAC -> "TR1"
            ComponentType.RELAY -> "RL1"
            ComponentType.OPAMP -> "U1"
            else -> "MODEL"
        }
        Triple(
            modelName.ifEmpty { defaultModel },
            paramsMap,
            component.type
        )
    }

    var visualModelName by remember { mutableStateOf(parsedInitialModel.first) }
    
    // States for parameters
    var paramIs by remember { mutableStateOf(parsedInitialModel.second["is"] ?: "1e-14") }
    var paramVt by remember { mutableStateOf(parsedInitialModel.second["vt"] ?: "0.02585") }
    var paramN by remember { mutableStateOf(parsedInitialModel.second["n"] ?: parsedInitialModel.second["ncoef"] ?: "1.0") }
    var paramBf by remember { mutableStateOf(parsedInitialModel.second["bf"] ?: parsedInitialModel.second["beta"] ?: "100.0") }

    var paramVto by remember { mutableStateOf(parsedInitialModel.second["vto"] ?: parsedInitialModel.second["vth"] ?: parsedInitialModel.second["vth_n"] ?: "2.0") }
    var paramKp by remember { mutableStateOf(parsedInitialModel.second["kp"] ?: parsedInitialModel.second["betamos"] ?: "1e-3") }

    var paramVtrigger by remember { mutableStateOf(parsedInitialModel.second["vtrigger"] ?: parsedInitialModel.second["vgt"] ?: parsedInitialModel.second["vt"] ?: if (component.type == ComponentType.RELAY) "3.0" else "0.7") }
    var paramVholding by remember { mutableStateOf(parsedInitialModel.second["vholding"] ?: parsedInitialModel.second["vhold"] ?: "0.1") }
    var paramRgate by remember { mutableStateOf(parsedInitialModel.second["rgate"] ?: parsedInitialModel.second["rg"] ?: "1e5") }
    var paramRon by remember { mutableStateOf(parsedInitialModel.second["ron"] ?: if (component.type == ComponentType.RELAY) "0.1" else "1.0") }
    var paramRoff by remember { mutableStateOf(parsedInitialModel.second["roff"] ?: "1e7") }
    var paramRcoil by remember { mutableStateOf(parsedInitialModel.second["rcoil"] ?: parsedInitialModel.second["rc"] ?: "100.0") }

    var paramGain by remember { mutableStateOf(parsedInitialModel.second["gain"] ?: parsedInitialModel.second["a"] ?: "100000.0") }
    var paramRout by remember { mutableStateOf(parsedInitialModel.second["rout"] ?: parsedInitialModel.second["ro"] ?: "50.0") }
    var paramVmax by remember { mutableStateOf(parsedInitialModel.second["vmax"] ?: parsedInitialModel.second["vsat"] ?: "12.0") }

    val context = LocalContext.current
    var isModelImportExpanded by remember { mutableStateOf(false) }
    var pastedModelText by remember { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val text = inputStream.bufferedReader().use { it.readText() }
                    val (importedName, parsedMap) = parseModelParams(text)
                    if (importedName.isNotEmpty()) {
                        visualModelName = importedName
                    }
                    if (parsedMap.isNotEmpty()) {
                        // Update individual parameter states
                        parsedMap["is"]?.let { paramIs = it }
                        parsedMap["vt"]?.let { paramVt = it }
                        (parsedMap["n"] ?: parsedMap["ncoef"])?.let { paramN = it }
                        (parsedMap["bf"] ?: parsedMap["beta"])?.let { paramBf = it }
                        (parsedMap["vto"] ?: parsedMap["vth"] ?: parsedMap["vth_n"])?.let { paramVto = it }
                        (parsedMap["kp"] ?: parsedMap["betamos"])?.let { paramKp = it }
                        (parsedMap["vtrigger"] ?: parsedMap["vgt"] ?: parsedMap["vt"])?.let { paramVtrigger = it }
                        (parsedMap["vholding"] ?: parsedMap["vhold"])?.let { paramVholding = it }
                        (parsedMap["rgate"] ?: parsedMap["rg"])?.let { paramRgate = it }
                        parsedMap["ron"]?.let { paramRon = it }
                        parsedMap["roff"]?.let { paramRoff = it }
                        (parsedMap["rcoil"] ?: parsedMap["rc"])?.let { paramRcoil = it }
                        (parsedMap["gain"] ?: parsedMap["a"])?.let { paramGain = it }
                        (parsedMap["rout"] ?: parsedMap["ro"])?.let { paramRout = it }
                        (parsedMap["vmax"] ?: parsedMap["vsat"])?.let { paramVmax = it }

                        // Rebuild spice string
                        valueStr = rebuildSpiceString(
                            component.type, importedName.ifEmpty { visualModelName },
                            parsedMap["is"] ?: paramIs,
                            parsedMap["vt"] ?: paramVt,
                            parsedMap["n"] ?: parsedMap["ncoef"] ?: paramN,
                            parsedMap["bf"] ?: parsedMap["beta"] ?: paramBf,
                            parsedMap["vto"] ?: parsedMap["vth"] ?: parsedMap["vth_n"] ?: paramVto,
                            parsedMap["kp"] ?: parsedMap["betamos"] ?: paramKp,
                            parsedMap["vtrigger"] ?: parsedMap["vgt"] ?: parsedMap["vt"] ?: paramVtrigger,
                            parsedMap["vholding"] ?: parsedMap["vhold"] ?: paramVholding,
                            parsedMap["rgate"] ?: parsedMap["rg"] ?: paramRgate,
                            parsedMap["ron"] ?: paramRon,
                            parsedMap["roff"] ?: paramRoff,
                            parsedMap["rcoil"] ?: parsedMap["rc"] ?: paramRcoil,
                            parsedMap["gain"] ?: parsedMap["a"] ?: paramGain,
                            parsedMap["rout"] ?: parsedMap["ro"] ?: paramRout,
                            parsedMap["vmax"] ?: parsedMap["vsat"] ?: paramVmax
                        )
                        onValueChangeInRealTime?.invoke(valueStr)
                        Toast.makeText(context, "Successfully imported model from file: $importedName", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Found no valid parameters inside model file.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to read file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }



    fun updateParamStatesFromStr(str: String) {
        val (modelName, paramsMap) = parseModelParams(str)
        if (modelName.isNotEmpty()) {
            visualModelName = modelName
        }
        paramsMap["is"]?.let { paramIs = it }
        paramsMap["vt"]?.let { paramVt = it }
        (paramsMap["n"] ?: paramsMap["ncoef"])?.let { paramN = it }
        (paramsMap["bf"] ?: paramsMap["beta"])?.let { paramBf = it }
        (paramsMap["vto"] ?: paramsMap["vth"] ?: paramsMap["vth_n"])?.let { paramVto = it }
        (paramsMap["kp"] ?: paramsMap["betamos"])?.let { paramKp = it }
        (paramsMap["vtrigger"] ?: paramsMap["vgt"] ?: paramsMap["vt"])?.let { paramVtrigger = it }
        (paramsMap["vholding"] ?: paramsMap["vhold"])?.let { paramVholding = it }
        (paramsMap["rgate"] ?: paramsMap["rg"])?.let { paramRgate = it }
        paramsMap["ron"]?.let { paramRon = it }
        paramsMap["roff"]?.let { paramRoff = it }
        (paramsMap["rcoil"] ?: paramsMap["rc"])?.let { paramRcoil = it }
        (paramsMap["gain"] ?: paramsMap["a"])?.let { paramGain = it }
        (paramsMap["rout"] ?: paramsMap["ro"])?.let { paramRout = it }
        (paramsMap["vmax"] ?: paramsMap["vsat"])?.let { paramVmax = it }
    }

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
                    val isComplexActiveComponent = component.type in setOf(
                        ComponentType.DIODE,
                        ComponentType.TRANSISTOR_NPN,
                        ComponentType.MOSFET_N,
                        ComponentType.THYRISTOR,
                        ComponentType.TRIAC,
                        ComponentType.RELAY,
                        ComponentType.OPAMP
                    )

                    if (isComplexActiveComponent) {
                        Text(
                            text = "Device SPICE Model Builder",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { isModelImportExpanded = !isModelImportExpanded }
                                        .padding(vertical = 4.dp)
                                ) {
                                    Text("📥", modifier = Modifier.padding(end = 8.dp), fontSize = 18.sp)
                                    Text(
                                        text = "Import External SPICE Model (.txt)",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { isModelImportExpanded = !isModelImportExpanded },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Text(if (isModelImportExpanded) "▲" else "▼", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                
                                if (isModelImportExpanded) {
                                    Column(
                                        modifier = Modifier.padding(top = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Paste a PSpice/LTspice .MODEL text block or select a model card (.txt) file. Calculated parameters will be automatically updated.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        OutlinedTextField(
                                            value = pastedModelText,
                                            onValueChange = { pastedModelText = it },
                                            placeholder = {
                                                Text(
                                                    "Paste model text here...\ne.g.\n.MODEL Q2n2222a npn\n+IS=3.88e-14 BF=929.8\n...",
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(110.dp)
                                                .testTag("dialog_model_paste_input"),
                                            textStyle = MaterialTheme.typography.bodySmall
                                        )

                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Button(
                                                onClick = {
                                                    if (pastedModelText.isBlank()) {
                                                        Toast.makeText(context, "Please paste model text first", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        val (importedName, parsedMap) = parseModelParams(pastedModelText)
                                                        if (importedName.isNotEmpty()) {
                                                            visualModelName = importedName
                                                        }
                                                        if (parsedMap.isNotEmpty()) {
                                                            // Update individual parameter states
                                                            parsedMap["is"]?.let { paramIs = it }
                                                            parsedMap["vt"]?.let { paramVt = it }
                                                            (parsedMap["n"] ?: parsedMap["ncoef"])?.let { paramN = it }
                                                            (parsedMap["bf"] ?: parsedMap["beta"])?.let { paramBf = it }
                                                            (parsedMap["vto"] ?: parsedMap["vth"] ?: parsedMap["vth_n"])?.let { paramVto = it }
                                                            (parsedMap["kp"] ?: parsedMap["betamos"])?.let { paramKp = it }
                                                            (parsedMap["vtrigger"] ?: parsedMap["vgt"] ?: parsedMap["vt"])?.let { paramVtrigger = it }
                                                            (parsedMap["vholding"] ?: parsedMap["vhold"])?.let { paramVholding = it }
                                                            (parsedMap["rgate"] ?: parsedMap["rg"])?.let { paramRgate = it }
                                                            parsedMap["ron"]?.let { paramRon = it }
                                                            parsedMap["roff"]?.let { paramRoff = it }
                                                            (parsedMap["rcoil"] ?: parsedMap["rc"])?.let { paramRcoil = it }
                                                            (parsedMap["gain"] ?: parsedMap["a"])?.let { paramGain = it }
                                                            (parsedMap["rout"] ?: parsedMap["ro"])?.let { paramRout = it }
                                                            (parsedMap["vmax"] ?: parsedMap["vsat"])?.let { paramVmax = it }

                                                            // Rebuild spice string
                                                            valueStr = rebuildSpiceString(
                                                                component.type, importedName.ifEmpty { visualModelName },
                                                                parsedMap["is"] ?: paramIs,
                                                                parsedMap["vt"] ?: paramVt,
                                                                parsedMap["n"] ?: parsedMap["ncoef"] ?: paramN,
                                                                parsedMap["bf"] ?: parsedMap["beta"] ?: paramBf,
                                                                parsedMap["vto"] ?: parsedMap["vth"] ?: parsedMap["vth_n"] ?: paramVto,
                                                                parsedMap["kp"] ?: parsedMap["betamos"] ?: paramKp,
                                                                parsedMap["vtrigger"] ?: parsedMap["vgt"] ?: parsedMap["vt"] ?: paramVtrigger,
                                                                parsedMap["vholding"] ?: parsedMap["vhold"] ?: paramVholding,
                                                                parsedMap["rgate"] ?: parsedMap["rg"] ?: paramRgate,
                                                                parsedMap["ron"] ?: paramRon,
                                                                parsedMap["roff"] ?: paramRoff,
                                                                parsedMap["rcoil"] ?: parsedMap["rc"] ?: paramRcoil,
                                                                parsedMap["gain"] ?: parsedMap["a"] ?: paramGain,
                                                                parsedMap["rout"] ?: parsedMap["ro"] ?: paramRout,
                                                                parsedMap["vmax"] ?: parsedMap["vsat"] ?: paramVmax
                                                            )
                                                            onValueChangeInRealTime?.invoke(valueStr)
                                                            Toast.makeText(context, "Successfully imported model: $importedName", Toast.LENGTH_SHORT).show()
                                                            isModelImportExpanded = false
                                                        } else {
                                                            Toast.makeText(context, "No valid model parameters found. Please check format.", Toast.LENGTH_LONG).show()
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.weight(1f).testTag("button_parse_model_text"),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text("Parse Text", style = MaterialTheme.typography.bodySmall)
                                            }

                                            OutlinedButton(
                                                onClick = {
                                                    try {
                                                        filePickerLauncher.launch("text/*")
                                                    } catch (e: Exception) {
                                                        // Fallback in case device picker is missing mime type parser
                                                        filePickerLauncher.launch("*/*")
                                                    }
                                                },
                                                modifier = Modifier.weight(1.2f).testTag("button_import_model_file"),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text("Import File", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Model name
                                OutlinedTextField(
                                    value = visualModelName,
                                    onValueChange = { newVal ->
                                        visualModelName = newVal
                                        valueStr = rebuildSpiceString(
                                            component.type, newVal, paramIs, paramVt, paramN, paramBf,
                                            paramVto, paramKp, paramVtrigger, paramVholding, paramRgate,
                                            paramRon, paramRoff, paramRcoil, paramGain, paramRout, paramVmax
                                        )
                                        onValueChangeInRealTime?.invoke(valueStr)
                                    },
                                    label = { Text("Model Name / Part ID") },
                                    placeholder = { Text("e.g. 1N4148, 2N2222") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().testTag("dialog_model_name_input")
                                )

                                when (component.type) {
                                    ComponentType.DIODE -> {
                                        Text("Diode Parameters", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedTextField(
                                                value = paramIs,
                                                onValueChange = { newVal ->
                                                    paramIs = newVal
                                                    valueStr = rebuildSpiceString(
                                                        component.type, visualModelName, newVal, paramVt, paramN, paramBf,
                                                        paramVto, paramKp, paramVtrigger, paramVholding, paramRgate,
                                                        paramRon, paramRoff, paramRcoil, paramGain, paramRout, paramVmax
                                                    )
                                                    onValueChangeInRealTime?.invoke(valueStr)
                                                },
                                                label = { Text("Is (Sat. Current)") },
                                                placeholder = { Text("e.g. 1e-14") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f).testTag("diode_is_input")
                                            )
                                            OutlinedTextField(
                                                value = paramN,
                                                onValueChange = { newVal ->
                                                    paramN = newVal
                                                    valueStr = rebuildSpiceString(
                                                        component.type, visualModelName, paramIs, paramVt, newVal, paramBf,
                                                        paramVto, paramKp, paramVtrigger, paramVholding, paramRgate,
                                                        paramRon, paramRoff, paramRcoil, paramGain, paramRout, paramVmax
                                                    )
                                                    onValueChangeInRealTime?.invoke(valueStr)
                                                },
                                                label = { Text("N (Emission Coef)") },
                                                placeholder = { Text("1.0") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f).testTag("diode_n_input")
                                            )
                                        }
                                        OutlinedTextField(
                                            value = paramVt,
                                            onValueChange = { newVal ->
                                                paramVt = newVal
                                                valueStr = rebuildSpiceString(
                                                    component.type, visualModelName, paramIs, newVal, paramN, paramBf,
                                                    paramVto, paramKp, paramVtrigger, paramVholding, paramRgate,
                                                    paramRon, paramRoff, paramRcoil, paramGain, paramRout, paramVmax
                                                )
                                                onValueChangeInRealTime?.invoke(valueStr)
                                            },
                                            label = { Text("Vt (Thermal)") },
                                            placeholder = { Text("0.02585") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth().testTag("diode_vt_input")
                                        )
                                    }
                                    ComponentType.TRANSISTOR_NPN -> {
                                        Text("BJT Transistor Parameters", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedTextField(
                                                value = paramIs,
                                                onValueChange = { newVal ->
                                                    paramIs = newVal
                                                    valueStr = rebuildSpiceString(
                                                        component.type, visualModelName, newVal, paramVt, paramN, paramBf,
                                                        paramVto, paramKp, paramVtrigger, paramVholding, paramRgate,
                                                        paramRon, paramRoff, paramRcoil, paramGain, paramRout, paramVmax
                                                    )
                                                    onValueChangeInRealTime?.invoke(valueStr)
                                                },
                                                label = { Text("Is (Sat. Current)") },
                                                placeholder = { Text("e.g. 1e-14") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f).testTag("npn_is_input")
                                            )
                                            OutlinedTextField(
                                                value = paramBf,
                                                onValueChange = { newVal ->
                                                    paramBf = newVal
                                                    valueStr = rebuildSpiceString(
                                                        component.type, visualModelName, paramIs, paramVt, paramN, newVal,
                                                        paramVto, paramKp, paramVtrigger, paramVholding, paramRgate,
                                                        paramRon, paramRoff, paramRcoil, paramGain, paramRout, paramVmax
                                                    )
                                                    onValueChangeInRealTime?.invoke(valueStr)
                                                },
                                                label = { Text("Bf (Beta Gain)") },
                                                placeholder = { Text("100") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f).testTag("npn_bf_input")
                                            )
                                        }
                                        OutlinedTextField(
                                            value = paramVt,
                                            onValueChange = { newVal ->
                                                paramVt = newVal
                                                valueStr = rebuildSpiceString(
                                                    component.type, visualModelName, paramIs, newVal, paramN, paramBf,
                                                    paramVto, paramKp, paramVtrigger, paramVholding, paramRgate,
                                                    paramRon, paramRoff, paramRcoil, paramGain, paramRout, paramVmax
                                                )
                                                onValueChangeInRealTime?.invoke(valueStr)
                                            },
                                            label = { Text("Vt (Thermal)") },
                                            placeholder = { Text("0.02585") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth().testTag("npn_vt_input")
                                        )
                                    }
                                    ComponentType.MOSFET_N -> {
                                        Text("MOSFET Parameters", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedTextField(
                                                value = paramVto,
                                                onValueChange = { newVal ->
                                                    paramVto = newVal
                                                    valueStr = rebuildSpiceString(
                                                        component.type, visualModelName, paramIs, paramVt, paramN, paramBf,
                                                        newVal, paramKp, paramVtrigger, paramVholding, paramRgate,
                                                        paramRon, paramRoff, paramRcoil, paramGain, paramRout, paramVmax
                                                    )
                                                    onValueChangeInRealTime?.invoke(valueStr)
                                                },
                                                label = { Text("Vto (Threshold V)") },
                                                placeholder = { Text("2.0") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f).testTag("mosfet_vto_input")
                                            )
                                            OutlinedTextField(
                                                value = paramKp,
                                                onValueChange = { newVal ->
                                                    paramKp = newVal
                                                    valueStr = rebuildSpiceString(
                                                        component.type, visualModelName, paramIs, paramVt, paramN, paramBf,
                                                        paramVto, newVal, paramVtrigger, paramVholding, paramRgate,
                                                        paramRon, paramRoff, paramRcoil, paramGain, paramRout, paramVmax
                                                    )
                                                    onValueChangeInRealTime?.invoke(valueStr)
                                                },
                                                label = { Text("Kp (Transcon. Gain)") },
                                                placeholder = { Text("1e-3") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f).testTag("mosfet_kp_input")
                                            )
                                        }
                                    }
                                    ComponentType.THYRISTOR, ComponentType.TRIAC -> {
                                        Text("Thyristor/Triac Parameters", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedTextField(
                                                value = paramVtrigger,
                                                onValueChange = { newVal ->
                                                    paramVtrigger = newVal
                                                    valueStr = rebuildSpiceString(
                                                        component.type, visualModelName, paramIs, paramVt, paramN, paramBf,
                                                        paramVto, paramKp, newVal, paramVholding, paramRgate,
                                                        paramRon, paramRoff, paramRcoil, paramGain, paramRout, paramVmax
                                                    )
                                                    onValueChangeInRealTime?.invoke(valueStr)
                                                },
                                                label = { Text("Vtrigger (Gate)") },
                                                placeholder = { Text("0.7") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f).testTag("gate_vt_input")
                                            )
                                            OutlinedTextField(
                                                value = paramVholding,
                                                onValueChange = { newVal ->
                                                    paramVholding = newVal
                                                    valueStr = rebuildSpiceString(
                                                        component.type, visualModelName, paramIs, paramVt, paramN, paramBf,
                                                        paramVto, paramKp, paramVtrigger, newVal, paramRgate,
                                                        paramRon, paramRoff, paramRcoil, paramGain, paramRout, paramVmax
                                                    )
                                                    onValueChangeInRealTime?.invoke(valueStr)
                                                },
                                                label = { Text("Vholding") },
                                                placeholder = { Text("0.1") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f).testTag("gate_vhold_input")
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedTextField(
                                                value = paramRgate,
                                                onValueChange = { newVal ->
                                                    paramRgate = newVal
                                                    valueStr = rebuildSpiceString(
                                                        component.type, visualModelName, paramIs, paramVt, paramN, paramBf,
                                                        paramVto, paramKp, paramVtrigger, paramVholding, newVal,
                                                        paramRon, paramRoff, paramRcoil, paramGain, paramRout, paramVmax
                                                    )
                                                    onValueChangeInRealTime?.invoke(valueStr)
                                                },
                                                label = { Text("Rgate") },
                                                placeholder = { Text("1e5") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f).testTag("gate_rg_input")
                                            )
                                            OutlinedTextField(
                                                value = paramRon,
                                                onValueChange = { newVal ->
                                                    paramRon = newVal
                                                    valueStr = rebuildSpiceString(
                                                        component.type, visualModelName, paramIs, paramVt, paramN, paramBf,
                                                        paramVto, paramKp, paramVtrigger, paramVholding, paramRgate,
                                                        newVal, paramRoff, paramRcoil, paramGain, paramRout, paramVmax
                                                    )
                                                    onValueChangeInRealTime?.invoke(valueStr)
                                                },
                                                label = { Text("Ron (On Ohm)") },
                                                placeholder = { Text("1.0") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f).testTag("gate_ron_input")
                                            )
                                        }
                                        OutlinedTextField(
                                            value = paramRoff,
                                            onValueChange = { newVal ->
                                                paramRoff = newVal
                                                valueStr = rebuildSpiceString(
                                                    component.type, visualModelName, paramIs, paramVt, paramN, paramBf,
                                                    paramVto, paramKp, paramVtrigger, paramVholding, paramRgate,
                                                    paramRon, newVal, paramRcoil, paramGain, paramRout, paramVmax
                                                )
                                                onValueChangeInRealTime?.invoke(valueStr)
                                            },
                                            label = { Text("Roff (Off Ohm)") },
                                            placeholder = { Text("1e7") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth().testTag("gate_roff_input")
                                        )
                                    }
                                    ComponentType.RELAY -> {
                                        Text("Relay Parameters", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedTextField(
                                                value = paramVtrigger,
                                                onValueChange = { newVal ->
                                                    paramVtrigger = newVal
                                                    valueStr = rebuildSpiceString(
                                                        component.type, visualModelName, paramIs, paramVt, paramN, paramBf,
                                                        paramVto, paramKp, newVal, paramVholding, paramRgate,
                                                        paramRon, paramRoff, paramRcoil, paramGain, paramRout, paramVmax
                                                    )
                                                    onValueChangeInRealTime?.invoke(valueStr)
                                                },
                                                label = { Text("Vtrigger (Coil)") },
                                                placeholder = { Text("3.0") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f).testTag("relay_vt_input")
                                            )
                                            OutlinedTextField(
                                                value = paramRcoil,
                                                onValueChange = { newVal ->
                                                    paramRcoil = newVal
                                                    valueStr = rebuildSpiceString(
                                                        component.type, visualModelName, paramIs, paramVt, paramN, paramBf,
                                                        paramVto, paramKp, paramVtrigger, paramVholding, paramRgate,
                                                        paramRon, paramRoff, newVal, paramGain, paramRout, paramVmax
                                                    )
                                                    onValueChangeInRealTime?.invoke(valueStr)
                                                },
                                                label = { Text("Rcoil (Coil)") },
                                                placeholder = { Text("100") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f).testTag("relay_rc_input")
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedTextField(
                                                value = paramRon,
                                                onValueChange = { newVal ->
                                                    paramRon = newVal
                                                    valueStr = rebuildSpiceString(
                                                        component.type, visualModelName, paramIs, paramVt, paramN, paramBf,
                                                        paramVto, paramKp, paramVtrigger, paramVholding, paramRgate,
                                                        newVal, paramRoff, paramRcoil, paramGain, paramRout, paramVmax
                                                    )
                                                    onValueChangeInRealTime?.invoke(valueStr)
                                                },
                                                label = { Text("Ron (On)") },
                                                placeholder = { Text("0.1") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f).testTag("relay_ron_input")
                                            )
                                            OutlinedTextField(
                                                value = paramRoff,
                                                onValueChange = { newVal ->
                                                    paramRoff = newVal
                                                    valueStr = rebuildSpiceString(
                                                        component.type, visualModelName, paramIs, paramVt, paramN, paramBf,
                                                        paramVto, paramKp, paramVtrigger, paramVholding, paramRgate,
                                                        paramRon, newVal, paramRcoil, paramGain, paramRout, paramVmax
                                                    )
                                                    onValueChangeInRealTime?.invoke(valueStr)
                                                },
                                                label = { Text("Roff (Off)") },
                                                placeholder = { Text("1e7") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f).testTag("relay_roff_input")
                                            )
                                        }
                                    }
                                    ComponentType.OPAMP -> {
                                        Text("OpAmp Spec Parameters", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedTextField(
                                                value = paramGain,
                                                onValueChange = { newVal ->
                                                    paramGain = newVal
                                                    valueStr = rebuildSpiceString(
                                                        component.type, visualModelName, paramIs, paramVt, paramN, paramBf,
                                                        paramVto, paramKp, paramVtrigger, paramVholding, paramRgate,
                                                        paramRon, paramRoff, paramRcoil, newVal, paramRout, paramVmax
                                                    )
                                                    onValueChangeInRealTime?.invoke(valueStr)
                                                },
                                                label = { Text("Gain (OpenLoop)") },
                                                placeholder = { Text("100000.0") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f).testTag("opamp_gain_input")
                                            )
                                            OutlinedTextField(
                                                value = paramRout,
                                                onValueChange = { newVal ->
                                                    paramRout = newVal
                                                    valueStr = rebuildSpiceString(
                                                        component.type, visualModelName, paramIs, paramVt, paramN, paramBf,
                                                        paramVto, paramKp, paramVtrigger, paramVholding, paramRgate,
                                                        paramRon, paramRoff, paramRcoil, paramGain, newVal, paramVmax
                                                    )
                                                    onValueChangeInRealTime?.invoke(valueStr)
                                                },
                                                label = { Text("Rout (Output)") },
                                                placeholder = { Text("50.0") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f).testTag("opamp_rout_input")
                                            )
                                        }
                                        OutlinedTextField(
                                            value = paramVmax,
                                            onValueChange = { newVal ->
                                                paramVmax = newVal
                                                valueStr = rebuildSpiceString(
                                                    component.type, visualModelName, paramIs, paramVt, paramN, paramBf,
                                                    paramVto, paramKp, paramVtrigger, paramVholding, paramRgate,
                                                    paramRon, paramRoff, paramRcoil, paramGain, paramRout, newVal
                                                )
                                                onValueChangeInRealTime?.invoke(valueStr)
                                            },
                                            label = { Text("Vmax (Limit)") },
                                            placeholder = { Text("12.0") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth().testTag("opamp_vmax_input")
                                        )
                                    }
                                    else -> {}
                                }
                            }
                        }

                        OutlinedTextField(
                            value = valueStr,
                            onValueChange = { newValue ->
                                valueStr = newValue
                                updateParamStatesFromStr(newValue)
                                onValueChangeInRealTime?.invoke(newValue)
                            },
                            label = { Text("Raw SPICE Model Parameter Line") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("dialog_spice_value_input")
                        )
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
