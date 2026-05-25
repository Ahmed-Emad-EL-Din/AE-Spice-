package com.example.ui

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.example.engine.*
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RightPanelType {
    COMPONENTS,
    SIM_COMMANDS,
    GEMINI_AI
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SpiceAppUi() {
    val context = LocalContext.current

    var activeRightPanel by remember { mutableStateOf<RightPanelType?>(null) }
    var isPlotterExpanded by remember { mutableStateOf(false) }
    var showPresetsMenu by remember { mutableStateOf(false) }
    var showToolsMenu by remember { mutableStateOf(false) }

    // User session persistence & tracking state
    val sessionManager = remember { UserSessionManager(context) }
    var refreshSessionTrigger by remember { mutableStateOf(0) }
    var showGoogleAuthDialog by remember { mutableStateOf(false) }

    // Circuit state variables
    val components = remember { mutableStateListOf<Component>() }
    val wires = remember { mutableStateListOf<Wire>() }
    var selectedComponent by remember { mutableStateOf<Component?>(null) }

    // Undo/Redo Stacking History Engine
    val undoStack = remember { mutableStateListOf<Pair<List<Component>, List<Wire>>>() }
    val redoStack = remember { mutableStateListOf<Pair<List<Component>, List<Wire>>>() }

    fun pushUndoState() {
        undoStack.add(components.toList() to wires.toList())
        redoStack.clear()
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            val lastState = undoStack.removeAt(undoStack.lastIndex)
            redoStack.add(components.toList() to wires.toList())
            components.clear()
            components.addAll(lastState.first)
            wires.clear()
            wires.addAll(lastState.second)
            selectedComponent = null
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val nextState = redoStack.removeAt(redoStack.lastIndex)
            undoStack.add(components.toList() to wires.toList())
            components.clear()
            components.addAll(nextState.first)
            wires.clear()
            wires.addAll(nextState.second)
            selectedComponent = null
        }
    }
    
    // UI selections
    var activeTool by remember { mutableStateOf(WorkspaceTool.SELECT) }
    var placingComponentType by remember { mutableStateOf(ComponentType.RESISTOR) }
    var placingComponentValue by remember { mutableStateOf<String?>(null) }

    val hotkeyMap = remember {
        mutableStateMapOf<String, Pair<WorkspaceTool, String>>(
            "R" to (WorkspaceTool.PLACE_COMPONENT to "RESISTOR"),
            "C" to (WorkspaceTool.PLACE_COMPONENT to "CAPACITOR"),
            "L" to (WorkspaceTool.PLACE_COMPONENT to "INDUCTOR"),
            "D" to (WorkspaceTool.PLACE_COMPONENT to "DIODE"),
            "G" to (WorkspaceTool.PLACE_COMPONENT to "GROUND"),
            "V" to (WorkspaceTool.PLACE_COMPONENT to "VOLTAGE_SOURCE"),
            "I" to (WorkspaceTool.PLACE_COMPONENT to "CURRENT_SOURCE"),
            "M" to (WorkspaceTool.PLACE_COMPONENT to "MOSFET_N"),
            "Q" to (WorkspaceTool.PLACE_COMPONENT to "TRANSISTOR_NPN"),
            "O" to (WorkspaceTool.PLACE_COMPONENT to "OPAMP"),
            "W" to (WorkspaceTool.DRAW_WIRE to ""),
            "E" to (WorkspaceTool.ERASE to ""),
            "P" to (WorkspaceTool.PROBE to ""),
            "S" to (WorkspaceTool.SELECT to "")
        )
    }
    
    // Dialogs
    var showPropertiesDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showImportExportDialog by remember { mutableStateOf(false) }

    // Open Tabs & workspace states
    val openTabs = remember { mutableStateListOf<WorkspaceTab>() }
    var activeTabId by remember { mutableStateOf<String?>(null) }
    var reorderMenuTabId by remember { mutableStateOf<String?>(null) }

    val sharedPrefs = remember { context.getSharedPreferences("circuit_file_prefs", android.content.Context.MODE_PRIVATE) }
    val lastFolderKey = "last_saved_folder_path"
    var currentFolderFile by remember {
        val circuitsDir = java.io.File(context.filesDir, "circuits")
        if (!circuitsDir.exists()) {
            circuitsDir.mkdirs()
        }
        val lastPath = sharedPrefs.getString(lastFolderKey, null)
        val initialDir = if (lastPath != null) java.io.File(lastPath) else circuitsDir
        mutableStateOf(if (initialDir.exists()) initialDir else circuitsDir)
    }

    var showSaveAsDialog by remember { mutableStateOf(false) }
    var showOpenDialog by remember { mutableStateOf(false) }
    var showFileMenu by remember { mutableStateOf(false) }

    // Multi-select persistence variables
    val multiSelectedComponents = remember { mutableStateListOf<Component>() }
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var showMultiSelectActions by remember { mutableStateOf(false) }

    val clipboardComponents = remember { mutableStateListOf<Component>() }
    val clipboardWires = remember { mutableStateListOf<Wire>() }

    fun syncCurrentCanvasToActiveTab() {
        activeTabId?.let { tabId ->
            val idx = openTabs.indexOfFirst { it.id == tabId }
            if (idx != -1) {
                openTabs[idx] = openTabs[idx].copy(
                    components = components.toList(),
                    wires = wires.toList()
                )
            }
        }
    }

    fun selectTab(tabId: String) {
        syncCurrentCanvasToActiveTab()
        val targetIdx = openTabs.indexOfFirst { it.id == tabId }
        if (targetIdx != -1) {
            val targetTab = openTabs[targetIdx]
            components.clear()
            components.addAll(targetTab.components)
            wires.clear()
            wires.addAll(targetTab.wires)
            selectedComponent = null
            activeTabId = tabId
            
            // Clear multi select when switching tabs
            multiSelectedComponents.clear()
            isMultiSelectMode = false
            showMultiSelectActions = false
        }
    }

    fun exportToSchemaString(): String {
        val sb = StringBuilder()
        sb.append("{\n  \"components\": [\n")
        components.forEachIndexed { i, c ->
            sb.append("    {\"id\": \"${c.id}\", \"type\": \"${c.type.name}\", \"name\": \"${c.name}\", \"valueStr\": \"${c.valueStr}\", \"gridX\": ${c.gridX}, \"gridY\": ${c.gridY}, \"orientation\": \"${c.orientation.name}\"}")
            if (i < components.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ],\n  \"wires\": [\n")
        wires.forEachIndexed { i, w ->
            sb.append("    {\"startX\": ${w.start.x}, \"startY\": ${w.start.y}, \"endX\": ${w.end.x}, \"endY\": ${w.end.y}}")
            if (i < wires.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n}")
        return sb.toString()
    }

    fun parseJsonSchemaDirectly(json: String): Pair<List<Component>, List<Wire>> {
        val parsedComps = mutableListOf<Component>()
        val parsedWires = mutableListOf<Wire>()
        val compRegex = """\{"id":\s*"([^"]+)",\s*"type":\s*"([^"]+)",\s*"name":\s*"([^"]+)",\s*"valueStr":\s*"([^"]+)",\s*"gridX":\s*(-?\d+),\s*"gridY":\s*(-?\d+),\s*"orientation":\s*"([^"]+)"\}""".toRegex()
        val wireRegex = """\{"startX":\s*(-?\d+),\s*"startY":\s*(-?\d+),\s*"endX":\s*(-?\d+),\s*"endY":\s*(-?\d+)\}""".toRegex()
        
        compRegex.findAll(json).forEach { match ->
            val (id, typeStr, name, valStr, xStr, yStr, orientStr) = match.destructured
            val type = ComponentType.valueOf(typeStr)
            val orient = Orientation.valueOf(orientStr)
            parsedComps.add(Component(id, type, name, valStr, xStr.toInt(), yStr.toInt(), orient))
        }
        
        wireRegex.findAll(json).forEachIndexed { index, match ->
            val (sX, sY, eX, eY) = match.destructured
            parsedWires.add(Wire("W_import_${System.currentTimeMillis()}_$index", GridPoint(sX.toInt(), sY.toInt()), GridPoint(eX.toInt(), eY.toInt())))
        }
        return Pair(parsedComps, parsedWires)
    }

    fun handleCreateNew() {
        syncCurrentCanvasToActiveTab()
        components.clear()
        wires.clear()
        val newTab = WorkspaceTab(
            id = java.util.UUID.randomUUID().toString(),
            name = "untitled_${openTabs.size + 1}.json",
            file = null,
            components = emptyList(),
            wires = emptyList()
        )
        openTabs.add(newTab)
        activeTabId = newTab.id
        Toast.makeText(context, "Created empty canvas", Toast.LENGTH_SHORT).show()
    }

    fun handleSave() {
        syncCurrentCanvasToActiveTab()
        val activeIdx = openTabs.indexOfFirst { it.id == activeTabId }
        if (activeIdx == -1) return
        val tab = openTabs[activeIdx]
        if (tab.file != null) {
            try {
                val json = exportToSchemaString()
                tab.file.writeText(json)
                Toast.makeText(context, "Saved successfully", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Error saving: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        } else {
            showSaveAsDialog = true
        }
    }
    var showSubcircuitDialog by remember { mutableStateOf(false) }
    var showHotkeyOverlay by remember { mutableStateOf(false) }

    // Backup text fields
    var importText by remember { mutableStateOf("") }
    var subcircuitNameInput by remember { mutableStateOf("MY_IC") }
    
    // Solver and results
    var simSettings by remember { mutableStateOf(SimulationSettings()) }
    var simResult by remember { mutableStateOf<SimResult?>(null) }
    var runError by remember { mutableStateOf<String?>(null) }
    var isSimulating by remember { mutableStateOf(false) }
    var probedNodeToActivate by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    // Floating state: active selected tabs in phone view
    var bottomTabState by remember { mutableIntStateOf(0) } // 0: Plotter, 1: Catalog, 2: Simulation Controls

    // Let's create an initial RC circuit on startup so the app isn't blank
    LaunchedEffect(Unit) {
        if (openTabs.isEmpty()) {
            components.clear()
            wires.clear()
            loadRCTemplate(components, wires)
            val initialTab = WorkspaceTab(
                id = java.util.UUID.randomUUID().toString(),
                name = "RC_Filter.json",
                file = null,
                components = components.toList(),
                wires = wires.toList()
            )
            openTabs.add(initialTab)
            activeTabId = initialTab.id
        }
    }

    // Interactive probe resolver mapping clicked coordinate to SPICE node IDs
    fun handleProbeTapped(coordsStr: String) {
        val res = simResult ?: return
        
        // Let's sweep components/wires to see which node the grid coordinate maps to.
        // We look for a component pin or a wire that has this coordinate.
        // Once we find the element, we can search the coordinate inside the evaluated MNA map.
        // This is extremely simple: let's match the coordinate's root.
        // Since we can't easily reproduce the entire Union-Find root lookup here outside SpiceSolver,
        // we'll run a quick Union-Find check to map the grid coordinate to the solved Node name!
        val ufAux = SpiceSolver.UnionFind<GridPoint>()
        val pins = components.flatMap { it.getPins() }
        val wirePoints = wires.flatMap { listOf(it.start, it.end) }
        val allPoints = (pins + wirePoints).distinct()

        for (wire in wires) {
            ufAux.union(wire.start, wire.end)
            for (pt in allPoints) {
                if (wire.contains(pt)) {
                    ufAux.union(wire.start, pt)
                }
            }
        }
        for (pt1 in allPoints) {
            for (pt2 in allPoints) {
                if (pt1 == pt2) {
                    ufAux.union(pt1, pt2)
                }
            }
        }

        // Get coordinates from string e.g. "(x,y)"
        val numbers = coordsStr.removeSurrounding("(", ")").split(",")
        if (numbers.size == 2) {
            val targetPt = GridPoint(numbers[0].trim().toInt(), numbers[1].trim().toInt())
            val targetRoot = ufAux.find(targetPt)
            
            // Identify Ground root
            val groundPins = components.filter { it.type == ComponentType.GROUND }.flatMap { it.getPins() }
            val groundRoot = if (groundPins.isNotEmpty()) ufAux.find(groundPins.first()) else null

            // Assign a unique Node identification (Node 0 is always GROUND)
            val rootToNodeNumber = mutableMapOf<GridPoint, Int>()
            var currentNodeCounter = 1

            if (groundRoot != null) {
                rootToNodeNumber[groundRoot] = 0
            }

            // Map every grid point to its primary root and assigns numbers
            for (pt in allPoints) {
                val root = ufAux.find(pt)
                var nodeNum = rootToNodeNumber[root]
                if (nodeNum == null) {
                    nodeNum = currentNodeCounter++
                    rootToNodeNumber[root] = nodeNum
                }
            }

            if (groundRoot == null) {
                val firstRoot = allPoints.firstOrNull()?.let { ufAux.find(it) }
                if (firstRoot != null) {
                    rootToNodeNumber[firstRoot] = 0
                }
            }

            // Get target node number solved
            val targetNodeNum = rootToNodeNumber[targetRoot]
            if (targetNodeNum == 0) {
                Toast.makeText(context, "Probed: 0 (GND) = 0.0V", Toast.LENGTH_SHORT).show()
                return
            }

            if (targetNodeNum != null) {
                val mappedNodeName = "N$targetNodeNum"
                if (res.nodeVoltages.containsKey(mappedNodeName)) {
                    Toast.makeText(context, "Probed Voltage Node $mappedNodeName 🟢 Activated on Chart!", Toast.LENGTH_SHORT).show()
                    probedNodeToActivate = mappedNodeName
                } else {
                    Toast.makeText(context, "Probed Voltage Node $mappedNodeName (No simulation data available)", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "No active node mapped at coordinates $coordsStr", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun importFromSchemaString(json: String) {
        try {
            pushUndoState()
            components.clear()
            wires.clear()
            
            // Regex parsing avoids complex class loader dependencies
            val compRegex = """\{"id":\s*"([^"]+)",\s*"type":\s*"([^"]+)",\s*"name":\s*"([^"]+)",\s*"valueStr":\s*"([^"]+)",\s*"gridX":\s*(-?\d+),\s*"gridY":\s*(-?\d+),\s*"orientation":\s*"([^"]+)"\}""".toRegex()
            val wireRegex = """\{"startX":\s*(-?\d+),\s*"startY":\s*(-?\d+),\s*"endX":\s*(-?\d+),\s*"endY":\s*(-?\d+)\}""".toRegex()
            
            compRegex.findAll(json).forEach { match ->
                val (id, typeStr, name, valStr, xStr, yStr, orientStr) = match.destructured
                val type = ComponentType.valueOf(typeStr)
                val orient = Orientation.valueOf(orientStr)
                components.add(Component(id, type, name, valStr, xStr.toInt(), yStr.toInt(), orient))
            }
            
            wireRegex.findAll(json).forEach { match ->
                val (sX, sY, eX, eY) = match.destructured
                wires.add(com.example.engine.Wire("W_import_${System.currentTimeMillis()}_${wires.size}", GridPoint(sX.toInt(), sY.toInt()), GridPoint(eX.toInt(), eY.toInt())))
            }
            Toast.makeText(context, "Loaded Blueprint Schema successfully!", Toast.LENGTH_SHORT).show()
        } catch(e: Exception) {
            Toast.makeText(context, "Blueprint parse error: check JSON schema integrity.", Toast.LENGTH_SHORT).show()
        }
    }

    fun registerCurrentSubcircuit(name: String) {
        if (components.isEmpty()) return
        val portsList = components.filter { it.type == ComponentType.PORT }.map { GridPoint(it.gridX, it.gridY) }
        val portComponents = components.filter { it.type == ComponentType.PORT }
        val portNamesList = portComponents.map { it.valueStr }
        
        val template = com.example.engine.SubcircuitTemplate(
            id = name,
            name = name,
            ports = if (portsList.isNotEmpty()) portsList else listOf(GridPoint(-1, 0), GridPoint(1, 0)),
            portNames = if (portNamesList.isNotEmpty()) portNamesList else listOf("1", "2"),
            components = components.filter { it.type != ComponentType.PORT }.toList(),
            wires = wires.toList()
        )
        SubcircuitRegistry.templates[name] = template
        Toast.makeText(context, "Subcircuit '$name' successfully registered as reusable Integrated Circuit!", Toast.LENGTH_SHORT).show()
    }

    fun duplicateSelectedComponent() {
        selectedComponent?.let { comp ->
            pushUndoState()
            val newId = "${comp.type.name}_copy_${System.currentTimeMillis()}"
            val suffix = components.count { it.type == comp.type } + 1
            val prefix = when(comp.type) {
                ComponentType.RESISTOR -> "R"
                ComponentType.CAPACITOR -> "C"
                ComponentType.INDUCTOR -> "L"
                ComponentType.DIODE -> "D"
                ComponentType.VOLTAGE_SOURCE -> "V"
                ComponentType.CURRENT_SOURCE -> "I"
                ComponentType.GROUND -> "GND"
                ComponentType.TRANSISTOR_NPN -> "Q"
                ComponentType.MOSFET_N -> "M"
                ComponentType.THYRISTOR -> "SCR"
                ComponentType.RELAY -> "RL"
                ComponentType.TRIAC -> "TR"
                ComponentType.OPAMP -> "U"
                ComponentType.SUBCIRCUIT -> "X"
                ComponentType.PORT -> "P"
            }
            val newComp = comp.copy(
                id = newId,
                name = "$prefix$suffix",
                gridX = comp.gridX + 2,
                gridY = comp.gridY + 2
            )
            components.add(newComp)
            selectedComponent = newComp
            Toast.makeText(context, "Duplicated Part ${comp.name} successfully!", Toast.LENGTH_SHORT).show()
        }
    }

    // Solver execution call
    fun runSpiceSimulation() {
        isSimulating = true
        runError = null
        
        val componentsSnapshot = components.toList()
        val wiresSnapshot = wires.toList()
        val settingsSnapshot = simSettings.copy()

        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val solver = SpiceSolver()
                    solver.simulate(componentsSnapshot, wiresSnapshot, settingsSnapshot)
                }
                if (result.timePoints.isEmpty()) {
                    runError = "Simulation did not produce any output data points. Check circuit connectivity."
                    simResult = null
                } else {
                    simResult = result
                    Toast.makeText(context, "Simulation Finished Successfully!", Toast.LENGTH_SHORT).show()
                    // Auto switch tab to chart waveform plotter
                    bottomTabState = 0
                }
            } catch (e: Exception) {
                runError = "Matrix solver failed: ${e.localizedMessage ?: "circuit singularity / floating nodelist"}. Try adding connections to GND."
                simResult = null
            } finally {
                isSimulating = false
            }
        }
    }

    // Fast, silent simulation execution for real-time slider updates
    fun runSpiceSimulationQuietly() {
        val componentsSnapshot = components.toList()
        val wiresSnapshot = wires.toList()
        val settingsSnapshot = simSettings.copy()

        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val solver = SpiceSolver()
                    solver.simulate(componentsSnapshot, wiresSnapshot, settingsSnapshot)
                }
                if (result.timePoints.isNotEmpty()) {
                    simResult = result
                    runError = null
                }
            } catch (e: Exception) {
                // Quietly bypass intermediate solver singularities
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "AE Spice Studio",
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.SansSerif,
                            letterSpacing = 0.5.sp,
                            fontSize = 18.sp
                        )
                        Spacer(modifier = Modifier.width(16.dp))

                        // Presets Dropdown
                        Box {
                            TextButton(
                                onClick = { showPresetsMenu = true },
                                modifier = Modifier.testTag("nav_presets_btn")
                            ) {
                                Text("Presets", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                            DropdownMenu(
                                expanded = showPresetsMenu,
                                onDismissRequest = { showPresetsMenu = false },
                                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp))
                            ) {
                                DropdownMenuItem(
                                    text = { Text("RC Charging Preset", fontSize = 13.sp) },
                                    onClick = {
                                        showPresetsMenu = false
                                        selectedComponent = null
                                        simResult = null
                                        components.clear()
                                        wires.clear()
                                        loadRCTemplate(components, wires)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Diode Rectification Preset", fontSize = 13.sp) },
                                    onClick = {
                                        showPresetsMenu = false
                                        selectedComponent = null
                                        simResult = null
                                        components.clear()
                                        wires.clear()
                                        loadRectifierTemplate(components, wires)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("RLC Bandpass Preset", fontSize = 13.sp) },
                                    onClick = {
                                        showPresetsMenu = false
                                        selectedComponent = null
                                        simResult = null
                                        components.clear()
                                        wires.clear()
                                        loadRLCTemplate(components, wires)
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // File / IC Tools Dropdown
                        Box {
                            TextButton(
                                onClick = { showToolsMenu = true },
                                modifier = Modifier.testTag("nav_tools_btn")
                            ) {
                                Text("IC / File", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                            DropdownMenu(
                                expanded = showToolsMenu,
                                onDismissRequest = { showToolsMenu = false },
                                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp))
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Import Blueprint", fontSize = 13.sp) },
                                    onClick = {
                                        showToolsMenu = false
                                        importText = ""
                                        showImportExportDialog = true
                                    },
                                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Export Blueprint", fontSize = 13.sp) },
                                    onClick = {
                                        showToolsMenu = false
                                        importText = exportToSchemaString()
                                        showImportExportDialog = true
                                    },
                                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Build Reusable IC", fontSize = 13.sp) },
                                    onClick = {
                                        showToolsMenu = false
                                        subcircuitNameInput = "MY_IC_${(10..99).random()}"
                                        showSubcircuitDialog = true
                                    },
                                    leadingIcon = { Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                )
                            }
                        }
                    }
                },
                actions = {
                    // Google Profile / Credits configure
                    IconButton(
                        onClick = { showGoogleAuthDialog = true },
                        modifier = Modifier.padding(end = 4.dp).testTag("google_profile_btn")
                    ) {
                        if (sessionManager.isSignedIn) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4285F4)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = sessionManager.userName.take(1).uppercase(),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = "Google Accounts Credentials",
                                tint = Color(0xFFA1A3A5)
                            )
                        }
                    }

                    // Undo Jumper
                    IconButton(
                        onClick = { undo() },
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Undo", tint = Color.LightGray)
                    }

                    // Run simulation fab
                    Button(
                        onClick = { runSpiceSimulation() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFD1E4FF), // Elegant Dark Primary Accent Blue
                            contentColor = Color(0xFF00315C) // Contrast dark blue
                        ),
                        modifier = Modifier.padding(end = 4.dp).testTag("run_simulation_btn")
                    ) {
                        if (isSimulating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color(0xFF00315C),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Run")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("RUN", fontWeight = FontWeight.Bold)
                        }
                    }

                    // 3-dots button on the right to run button
                    Box {
                        IconButton(
                            onClick = { showFileMenu = true },
                            modifier = Modifier.padding(end = 8.dp).testTag("three_dots_file_menu_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "File Actions Menu",
                                tint = Color.White
                            )
                        }

                        DropdownMenu(
                            expanded = showFileMenu,
                            onDismissRequest = { showFileMenu = false },
                            modifier = Modifier.background(Color(0xFF202124))
                        ) {
                            DropdownMenuItem(
                                text = { Text("Create New", color = Color.White) },
                                onClick = {
                                    showFileMenu = false
                                    handleCreateNew()
                                },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = "New", tint = Color.LightGray) }
                            )
                            DropdownMenuItem(
                                text = { Text("Open System File...", color = Color.White) },
                                onClick = {
                                    showFileMenu = false
                                    showOpenDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.List, contentDescription = "Open", tint = Color.LightGray) }
                            )
                            DropdownMenuItem(
                                text = { Text("Save Schematic", color = Color.White) },
                                onClick = {
                                    showFileMenu = false
                                    handleSave()
                                },
                                leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Save", tint = Color.LightGray) }
                            )
                            DropdownMenuItem(
                                text = { Text("Save As...", color = Color.White) },
                                onClick = {
                                    showFileMenu = false
                                    showSaveAsDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = "Save As", tint = Color.LightGray) }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1C1E)
                )
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val widthPx = constraints.maxWidth
            val heightPx = constraints.maxHeight

            // 1. GLOBAL VIEWPORT EXPANSION: Fills 100% width and height
            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                SchematicWorkspace(
                    components = components,
                    wires = wires,
                    selectedComponent = selectedComponent,
                    activeTool = activeTool,
                    placingType = placingComponentType,
                    simResult = simResult,
                    onSelectComponent = { selectedComponent = it },
                    onAddComponent = { pushUndoState(); components.add(it) },
                    onAddWire = { pushUndoState(); wires.add(it) },
                    onDeleteComponent = { pushUndoState(); components.remove(it) },
                    onDeleteWire = { pushUndoState(); wires.remove(it) },
                    onProbeNode = { handleProbeTapped(it) },
                    onUpdateComponent = { oldComp, newComp ->
                        val idx = components.indexOfFirst { it.id == oldComp.id }
                        if (idx != -1) {
                            pushUndoState()
                            components[idx] = newComp
                            if (selectedComponent?.id == newComp.id) {
                                selectedComponent = newComp
                            }
                        }
                    },
                    onDoubleTapComponent = {
                        selectedComponent = it
                        showPropertiesDialog = true
                    },
                    onCanvasClick = {
                        // Smart Dismiss empty workspace click:
                        activeRightPanel = null
                    },
                    placingValue = placingComponentValue,
                    modifier = Modifier.fillMaxSize(),
                    multiSelectedComponents = multiSelectedComponents,
                    isMultiSelectMode = isMultiSelectMode,
                    onMultiSelectModeChange = { 
                        isMultiSelectMode = it
                        if (it) {
                            selectedComponent = null
                        }
                    },
                    showMultiSelectActions = showMultiSelectActions,
                    onShowMultiSelectActionsChange = { showMultiSelectActions = it },
                    clipboardComponents = clipboardComponents,
                    clipboardWires = clipboardWires,
                    onPushHistoryState = { pushUndoState() }
                )
            }

            // 2. FLOATING MODE TOOLBAR
            // Floats elegantly at the top center of the canvas area, completely responsive and clean.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // --- HORIZONTAL OPEN FILES VIEW ---
                    if (openTabs.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            openTabs.forEachIndexed { index, tab ->
                                val isActive = activeTabId == tab.id
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    elevation = CardDefaults.cardElevation(if (isActive) 6.dp else 2.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isActive) Color(0xFF00315C) else Color(0xFF2D3135)
                                    ),
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = if (isActive) Color(0xFFD1E4FF) else Color.Transparent
                                    ),
                                    modifier = Modifier
                                        .combinedClickable(
                                            onClick = {
                                                selectTab(tab.id)
                                            },
                                            onLongClick = {
                                                reorderMenuTabId = tab.id
                                            }
                                        )
                                        .testTag("tab_item_${tab.id}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Share,
                                            contentDescription = "File scheme indicator icon",
                                            tint = if (isActive) Color(0xFFD1E4FF) else Color.LightGray,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = tab.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isActive) Color.White else Color.LightGray,
                                            fontSize = 11.sp
                                        )
                                        IconButton(
                                            onClick = {
                                                // Close tab trigger
                                                syncCurrentCanvasToActiveTab()
                                                val tabIdx = openTabs.indexOfFirst { it.id == tab.id }
                                                if (tabIdx != -1) {
                                                    val closingActive = activeTabId == tab.id
                                                    openTabs.removeAt(tabIdx)
                                                    if (openTabs.isEmpty()) {
                                                        // Fallback creation
                                                        val fallback = WorkspaceTab(
                                                            id = java.util.UUID.randomUUID().toString(),
                                                            name = "Untitled.json",
                                                            file = null,
                                                            components = emptyList(),
                                                            wires = emptyList()
                                                        )
                                                        openTabs.add(fallback)
                                                        activeTabId = fallback.id
                                                        components.clear()
                                                        wires.clear()
                                                    } else if (closingActive) {
                                                        val nextActiveIdx = tabIdx.coerceAtMost(openTabs.size - 1)
                                                        selectTab(openTabs[nextActiveIdx].id)
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(16.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Close File Scheme Tab",
                                                tint = Color.Gray,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Reorder popover dialog overlay
                    reorderMenuTabId?.let { tabId ->
                        val targetIdx = openTabs.indexOfFirst { it.id == tabId }
                        if (targetIdx != -1) {
                            val targetTab = openTabs[targetIdx]
                            AlertDialog(
                                onDismissRequest = { reorderMenuTabId = null },
                                title = { Text("Organize Tab: ${targetTab.name}") },
                                text = {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        TextButton(
                                            onClick = {
                                                openTabs.removeAt(targetIdx)
                                                openTabs.add(0, targetTab)
                                                reorderMenuTabId = null
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move to First")
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Move to First Position", textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                                        }

                                        TextButton(
                                            onClick = {
                                                if (targetIdx > 0) {
                                                    openTabs.removeAt(targetIdx)
                                                    openTabs.add(targetIdx - 1, targetTab)
                                                }
                                                reorderMenuTabId = null
                                            },
                                            enabled = targetIdx > 0,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Move Left")
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Move Left (Forward)", textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                                        }

                                        TextButton(
                                            onClick = {
                                                if (targetIdx < openTabs.size - 1) {
                                                    openTabs.removeAt(targetIdx)
                                                    openTabs.add(targetIdx + 1, targetTab)
                                                }
                                                reorderMenuTabId = null
                                            },
                                            enabled = targetIdx < openTabs.size - 1,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Move Right")
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Move Right (Backward)", textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                                        }
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = { reorderMenuTabId = null }) {
                                        Text("Done")
                                    }
                                }
                            )
                        }
                    }

                    Card(
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .wrapContentWidth()
                            .padding(horizontal = 16.dp)
                            .testTag("floating_mode_toolbar")
                    ) {
                        ModeToolbar(
                            activeTool = activeTool,
                            onToolChange = { activeTool = it },
                            selectedComponent = if (isMultiSelectMode) null else selectedComponent,
                            onRotate = {
                                selectedComponent?.let { comp ->
                                    pushUndoState()
                                    val currentRot = comp.orientation
                                    val idx = (Orientation.values().indexOf(currentRot) + 1) % Orientation.values().size
                                    val nextRot = Orientation.values()[idx]
                                    
                                    val cIndex = components.indexOfFirst { it.id == comp.id }
                                    if (cIndex != -1) {
                                        components[cIndex] = comp.copy(orientation = nextRot)
                                        selectedComponent = components[cIndex]
                                        runSpiceSimulationQuietly()
                                    }
                                }
                            },
                            onDelete = {
                                selectedComponent?.let { comp ->
                                    pushUndoState()
                                    components.remove(comp)
                                    selectedComponent = null
                                    runSpiceSimulationQuietly()
                                }
                            },
                            onEdit = { showPropertiesDialog = true },
                            onUndo = { undo() },
                            onRedo = { redo() },
                            canUndo = undoStack.isNotEmpty(),
                            canRedo = redoStack.isNotEmpty(),
                            onCopy = { duplicateSelectedComponent() },
                            onHotkeyClick = { showHotkeyOverlay = true }
                        )
                    }
                }
            }

            // 3. COLLAPSIBLE RIGHT UTILITY DOCK CONTAINER
            // Features a pinned vertical dock (icon strip) and a beautiful spring-animated fly-out drawer.
            
            // Pinned dock bar
            Card(
                shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                elevation = CardDefaults.cardElevation(6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(56.dp)
                    .wrapContentHeight()
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Catalog / Components Toggle Icon Button
                    IconButton(
                        onClick = {
                            activeRightPanel = if (activeRightPanel == RightPanelType.COMPONENTS) null else RightPanelType.COMPONENTS
                        },
                        modifier = Modifier.testTag("dock_components_btn"),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (activeRightPanel == RightPanelType.COMPONENTS) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = "Components Box List",
                            tint = if (activeRightPanel == RightPanelType.COMPONENTS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Simulation Commands / Settings Toggle Icon Button
                    IconButton(
                        onClick = {
                            activeRightPanel = if (activeRightPanel == RightPanelType.SIM_COMMANDS) null else RightPanelType.SIM_COMMANDS
                        },
                        modifier = Modifier.testTag("dock_sim_commands_btn"),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (activeRightPanel == RightPanelType.SIM_COMMANDS) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Simulation Parameters Control",
                            tint = if (activeRightPanel == RightPanelType.SIM_COMMANDS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Gemini Intelligent Copilot Toggle Icon Button
                    IconButton(
                        onClick = {
                            activeRightPanel = if (activeRightPanel == RightPanelType.GEMINI_AI) null else RightPanelType.GEMINI_AI
                        },
                        modifier = Modifier.testTag("dock_gemini_ai_btn"),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (activeRightPanel == RightPanelType.GEMINI_AI) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Gemini Intelligent Assistant",
                            tint = if (activeRightPanel == RightPanelType.GEMINI_AI) Color(0xFFFBBC05) else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Slide-Out Panel Content
            val density = LocalDensity.current
            
            val initialWaveformBtnPos = remember(widthPx, heightPx) {
                val btnWidthPx = with(density) { 120.dp.toPx() }
                val btnHeightPx = with(density) { 48.dp.toPx() }
                Offset(
                    x = (widthPx - btnWidthPx) / 2f,
                    y = heightPx - btnHeightPx - with(density) { 16.dp.toPx() }
                )
            }

            var waveformBtnPos by remember { mutableStateOf(initialWaveformBtnPos) }

            if (activeRightPanel != null) {
                val initialDockPanelPos = remember(widthPx, heightPx) {
                    val paddingX = with(density) { 16.dp.toPx() }
                    val paddingY = with(density) { 80.dp.toPx() }
                    Offset(paddingX, paddingY)
                }
                DraggableFloatingWindow(
                    windowId = "right_panel_v2_${activeRightPanel?.name}",
                    initialPosition = initialDockPanelPos,
                    containerSize = IntSize(widthPx.toInt(), heightPx.toInt()),
                    modifier = Modifier.width(360.dp).height(500.dp),
                    headerContent = {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = when (activeRightPanel) {
                                    RightPanelType.COMPONENTS -> "Components"
                                    RightPanelType.SIM_COMMANDS -> "Simulation Command Setup"
                                    RightPanelType.GEMINI_AI -> "Gemini Circuit Advisor"
                                    else -> ""
                                },
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp
                            )
                            IconButton(onClick = { activeRightPanel = null }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close Panel",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            when (activeRightPanel) {
                                RightPanelType.COMPONENTS -> {
                                    ComponentCatalog(
                                        selectedType = placingComponentType,
                                        onSelectType = {
                                            placingComponentType = it
                                            activeTool = WorkspaceTool.PLACE_COMPONENT
                                            activeRightPanel = null
                                        }
                                    )
                                }
                                RightPanelType.SIM_COMMANDS -> {
                                    SimulationSettingsControl(
                                        settings = simSettings,
                                        onSettingsChange = { simSettings = it }
                                    )
                                }
                                RightPanelType.GEMINI_AI -> {
                                    GeminiChatPanel(
                                        components = components,
                                        wires = wires,
                                        simSettings = simSettings,
                                        simResult = simResult,
                                        onReplaceCircuit = { newComps, newWires ->
                                            pushUndoState()
                                            components.clear()
                                            components.addAll(newComps)
                                            wires.clear()
                                            wires.addAll(newWires)
                                            selectedComponent = null
                                        },
                                        onModifyParameters = { modifications ->
                                            pushUndoState()
                                            modifications.forEach { mod ->
                                                val idx = components.indexOfFirst { it.name.lowercase() == mod.name.lowercase() }
                                                if (idx != -1) {
                                                    components[idx] = components[idx].copy(valueStr = mod.valueStr)
                                                }
                                            }
                                            selectedComponent = null
                                        },
                                        onRunSimulation = { settings ->
                                            simSettings = settings
                                            runSpiceSimulation()
                                        },
                                        onShowSettings = { showGoogleAuthDialog = true },
                                        sessionManager = sessionManager
                                    )
                                }
                                null -> {}
                            }
                        }
                    }
                }
            }

            // 4. FLOATING WAVEFORM BUTTON & PLOTTER WINDOW
            
            if (!isPlotterExpanded) {
                DraggableFloatingWindow(
                    windowId = "waveform_trigger_btn_v2",
                    initialPosition = initialWaveformBtnPos,
                    containerSize = IntSize(widthPx.toInt(), heightPx.toInt()),
                    draggableBody = true,
                    onPositionChanged = { waveformBtnPos = it },
                    modifier = Modifier.wrapContentSize(),
                    headerContent = null
                ) {
                    Card(
                        onClick = { isPlotterExpanded = true },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info, // A wave-like placeholder or info symbol
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Waveform",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }

            if (isPlotterExpanded) {
                val initialPlotterPos = remember(widthPx, heightPx) {
                    val plotterHeightPx = with(density) { 380.dp.toPx() }
                    Offset(0f, heightPx - plotterHeightPx)
                }
                DraggableFloatingWindow(
                    windowId = "wave_plotter_window_v2",
                    initialPosition = initialPlotterPos,
                    containerSize = IntSize(widthPx.toInt(), heightPx.toInt()),
                    modifier = Modifier.fillMaxWidth().height(380.dp),
                    headerContent = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                // Little aesthetic pill grab handle
                                Box(
                                    modifier = Modifier
                                        .width(44.dp)
                                        .height(4.dp)
                                        .clip(CircleShape)
                                        .background(Color.Gray.copy(alpha = 0.5f))
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Waveform",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    IconButton(onClick = { isPlotterExpanded = false }, modifier = Modifier.size(24.dp)) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close Plotter",
                                            tint = Color.Gray,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            if (runError != null) {
                                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                                    Text(runError!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                                }
                            } else {
                                WaveformViewer(
                                    result = simResult ?: SimResult(emptyList(), "Time", emptyMap(), emptyMap()),
                                    probedNodeToActivate = probedNodeToActivate,
                                    onSelectNodeFromChart = { probedNodeToActivate = null }
                                )
                            }
                        }
                    }
                }
            }

            // --- FLOATING MULTI-SELECT OVERLAY ACTIONS BANNER ---
            if (isMultiSelectMode && showMultiSelectActions) {
                val multiSelectBottomPadding = if (isPlotterExpanded) {
                    388.dp
                } else {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val bottomPx = heightPx - waveformBtnPos.y + with(density) { 8.dp.toPx() }
                    with(density) { bottomPx.toDp() }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(bottom = multiSelectBottomPadding)
                        .zIndex(10f),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        elevation = CardDefaults.cardElevation(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF202124)),
                        border = BorderStroke(1.5.dp, Color(0xFF00FFCC)),
                        modifier = Modifier
                            .wrapContentSize()
                            .padding(horizontal = 24.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "${multiSelectedComponents.size} Selected",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00FFCC)
                            )
                            
                            Box(modifier = Modifier.width(1.dp).height(24.dp).background(Color.Gray))
                            
                            // Copy
                            IconButton(
                                onClick = {
                                    clipboardComponents.clear()
                                    clipboardComponents.addAll(multiSelectedComponents)
                                    clipboardWires.clear()
                                    val selectedPins = multiSelectedComponents.flatMap { it.getPins() }.toSet()
                                    val selWires = wires.filter { wire ->
                                        selectedPins.any { it.x == wire.start.x && it.y == wire.start.y } &&
                                        selectedPins.any { it.x == wire.end.x && it.y == wire.end.y }
                                    }
                                    clipboardWires.addAll(selWires)
                                    Toast.makeText(context, "Copied ${multiSelectedComponents.size} components", Toast.LENGTH_SHORT).show()
                                    showMultiSelectActions = false
                                }
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.Share, contentDescription = "Copy", tint = Color.White, modifier = Modifier.size(20.dp))
                                    Text("Copy", fontSize = 8.sp, color = Color.LightGray)
                                }
                            }
                            
                            // Cut
                            IconButton(
                                onClick = {
                                    clipboardComponents.clear()
                                    clipboardComponents.addAll(multiSelectedComponents)
                                    clipboardWires.clear()
                                    val selectedPins = multiSelectedComponents.flatMap { it.getPins() }.toSet()
                                    val selWires = wires.filter { wire ->
                                        selectedPins.any { it.x == wire.start.x && it.y == wire.start.y } &&
                                        selectedPins.any { it.x == wire.end.x && it.y == wire.end.y }
                                    }
                                    clipboardWires.addAll(selWires)
                                    
                                    pushUndoState()
                                    multiSelectedComponents.forEach { comp ->
                                        components.remove(comp)
                                    }
                                    selWires.forEach { w ->
                                        wires.remove(w)
                                    }
                                    multiSelectedComponents.clear()
                                    isMultiSelectMode = false
                                    showMultiSelectActions = false
                                    runSpiceSimulationQuietly()
                                    Toast.makeText(context, "Cut ${clipboardComponents.size} elements", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.LocationOn, contentDescription = "Cut", tint = Color.White, modifier = Modifier.size(20.dp))
                                    Text("Cut", fontSize = 8.sp, color = Color.LightGray)
                                }
                            }
                            

                            // Delete
                            IconButton(
                                onClick = {
                                    pushUndoState()
                                    val selectedPins = multiSelectedComponents.flatMap { it.getPins() }.toSet()
                                    val selWires = wires.filter { wire ->
                                        selectedPins.any { it.x == wire.start.x && it.y == wire.start.y } &&
                                        selectedPins.any { it.x == wire.end.x && it.y == wire.end.y }
                                    }
                                    multiSelectedComponents.forEach { comp ->
                                        components.remove(comp)
                                    }
                                    selWires.forEach { w ->
                                        wires.remove(w)
                                    }
                                    multiSelectedComponents.clear()
                                    isMultiSelectMode = false
                                    showMultiSelectActions = false
                                    runSpiceSimulationQuietly()
                                    Toast.makeText(context, "Deleted selection", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete selection", tint = Color.Red, modifier = Modifier.size(20.dp))
                                    Text("Delete", fontSize = 8.sp, color = Color.Red)
                                }
                            }
                            
                            // Close Selection mode
                            IconButton(
                                onClick = {
                                    multiSelectedComponents.clear()
                                    isMultiSelectMode = false
                                    showMultiSelectActions = false
                                }
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancel selection", tint = Color.Gray, modifier = Modifier.size(20.dp))
                                    Text("Cancel", fontSize = 8.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- DIALOG POPOUTS ---
    if (showGoogleAuthDialog) {
        GoogleAuthDialog(
            sessionManager = sessionManager,
            onDismiss = { showGoogleAuthDialog = false },
            onSessionUpdated = { refreshSessionTrigger++ }
        )
    }

    // Modify element parameter pop up
    if (showPropertiesDialog && selectedComponent != null) {
        ComponentPropertiesDialog(
            component = selectedComponent!!,
            onDismiss = { showPropertiesDialog = false },
            onSave = { updatedName, updatedVal, updatedOrient ->
                val comp = selectedComponent!!
                val idx = components.indexOfFirst { it.id == comp.id }
                if (idx != -1) {
                    components[idx] = comp.copy(
                        name = updatedName,
                        valueStr = updatedVal,
                        orientation = updatedOrient
                    )
                    selectedComponent = components[idx]
                    runSpiceSimulation()
                }
                showPropertiesDialog = false
            },
            onValueChangeInRealTime = { updatedVal ->
                val comp = selectedComponent
                if (comp != null) {
                    val idx = components.indexOfFirst { it.id == comp.id }
                    if (idx != -1) {
                        components[idx] = comp.copy(
                            valueStr = updatedVal
                        )
                        selectedComponent = components[idx]
                        runSpiceSimulationQuietly()
                    }
                }
            }
        )
    }

    if (showSaveAsDialog) {
        var newFileName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSaveAsDialog = false },
            title = { Text("Save As (Download Circuit to System)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Select name for your schematic file. The file will be stored in the circuits folder, remembering your last saved location automatically on reload.")
                    
                    OutlinedTextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        modifier = Modifier.fillMaxWidth().testTag("save_as_filename_input"),
                        label = { Text("File Name (e.g., filter_v1.json)") },
                        singleLine = true
                    )
                    
                    Text(
                        text = "Destination directory: ${currentFolderFile.absolutePath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFileName.isNotBlank()) {
                            val finalFile = java.io.File(currentFolderFile, if (newFileName.endsWith(".json")) newFileName else "$newFileName.json")
                            try {
                                val json = exportToSchemaString()
                                finalFile.writeText(json)
                                
                                // Save selection directory in SharedPrefs so it remembers next time!
                                sharedPrefs.edit().putString(lastFolderKey, currentFolderFile.absolutePath).apply()
                                
                                syncCurrentCanvasToActiveTab()
                                
                                // Update or Add tab matching this file
                                val currentIdx = openTabs.indexOfFirst { it.id == activeTabId }
                                if (currentIdx != -1) {
                                    openTabs[currentIdx] = openTabs[currentIdx].copy(
                                        name = finalFile.name,
                                        file = finalFile
                                    )
                                } else {
                                    val newTab = WorkspaceTab(
                                        id = java.util.UUID.randomUUID().toString(),
                                        name = finalFile.name,
                                        file = finalFile,
                                        components = components.toList(),
                                        wires = wires.toList()
                                    )
                                    openTabs.add(newTab)
                                    activeTabId = newTab.id
                                }
                                
                                showSaveAsDialog = false
                                Toast.makeText(context, "Saved to ${finalFile.name}", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Failed to save: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "File name cannot be empty", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Save File")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveAsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showOpenDialog) {
        val jsonFiles = currentFolderFile.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toList() ?: emptyList()
        AlertDialog(
            onDismissRequest = { showOpenDialog = false },
            title = { Text("Open Schematics (Multi-File Canvas)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Choose one or multiple file layers from '${currentFolderFile.name}' to open as horizontal tabs above the workspace.")
                    
                    if (jsonFiles.isEmpty()) {
                        Text("No schematics found. Please save a file first using 'Save As'!", color = Color.Gray, fontWeight = FontWeight.Bold)
                    } else {
                        LazyColumn(
                            modifier = Modifier.height(260.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(jsonFiles) { file ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            try {
                                                val json = file.readText()
                                                
                                                // Save folder path
                                                sharedPrefs.edit().putString(lastFolderKey, currentFolderFile.absolutePath).apply()
                                                
                                                // Parse components and wires safely using the regex-based schema parser
                                                val parsedPair = parseJsonSchemaDirectly(json)
                                                
                                                // Create a new tab
                                                val tabId = java.util.UUID.randomUUID().toString()
                                                val parsedTab = WorkspaceTab(
                                                    id = tabId,
                                                    name = file.name,
                                                    file = file,
                                                    components = parsedPair.first,
                                                    wires = parsedPair.second
                                                )
                                                
                                                // Add and active tab
                                                openTabs.add(parsedTab)
                                                selectTab(parsedTab.id)
                                                
                                                showOpenDialog = false
                                                Toast.makeText(context, "Opened ${file.name}", Toast.LENGTH_SHORT).show()
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Error decoding file: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2D3135))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = null, tint = Color.LightGray)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(file.name, fontWeight = FontWeight.SemiBold, color = Color.White)
                                            Text("Path: System Cache/circuits", fontSize = 10.sp, color = Color.Gray)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showOpenDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showImportExportDialog) {
        AlertDialog(
            onDismissRequest = { showImportExportDialog = false },
            title = { Text("Import / Export Schematic JSON", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Copy the JSON template below to export, or paste a saved JSON schema to load alternative circuits.", fontSize = 12.sp)
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        label = { Text("Schematic JSON Code") },
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            if (importText.isNotBlank()) {
                                importFromSchemaString(importText)
                                showImportExportDialog = false
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "Error parsing JSON: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                ) {
                    Text("Import & Load")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            importText = exportToSchemaString()
                            Toast.makeText(context, "Updated Export String!", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("Regenerate")
                    }
                    TextButton(onClick = { showImportExportDialog = false }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    if (showSubcircuitDialog) {
        AlertDialog(
            onDismissRequest = { showSubcircuitDialog = false },
            title = { Text("Create Custom IC (Subcircuit Template)", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Design modular nested blocks! Make sure you have placed " +
                        "Subcircuit Port (P) components on the canvas to act as the pins, " +
                        "with matching name labels (e.g. IN, OUT, VCC).",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    val portCount = components.count { it.type == ComponentType.PORT }
                    Text("Detected active pins count: $portCount", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (portCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)

                    OutlinedTextField(
                        value = subcircuitNameInput,
                        onValueChange = { subcircuitNameInput = it },
                        label = { Text("IC Model Name / Template ID") },
                        placeholder = { Text("e.g. MY_OPAMP, FILTER_3") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val templateName = subcircuitNameInput.trim()
                        if (templateName.isBlank()) {
                            Toast.makeText(context, "Please specify an IC template name!", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (components.count { it.type == ComponentType.PORT } == 0) {
                            Toast.makeText(context, "Failed: Place at least one Subcircuit Port (P) component on the canvas to act as pins first!", Toast.LENGTH_LONG).show()
                            return@Button
                        }
                        registerCurrentSubcircuit(templateName)
                        showSubcircuitDialog = false
                    }
                ) {
                    Text("Save & Register IC")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSubcircuitDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showHotkeyOverlay) {
        val keyboardController = LocalSoftwareKeyboardController.current
        AlertDialog(
            onDismissRequest = { 
                showHotkeyOverlay = false 
            },
            title = null,
            text = {
                var inputStr by remember { mutableStateOf("") }
                val focusRequester = remember { FocusRequester() }

                var showKeysList by remember { mutableStateOf(false) }
                var showEditArea by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(100)
                    focusRequester.requestFocus()
                    keyboardController?.show()
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Hotkey",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    // The text field that is focused to capture typing
                    OutlinedTextField(
                        value = inputStr,
                        onValueChange = { newValue ->
                            // Capture only the latest typed character
                            val charTyped = newValue.lastOrNull()?.toString()?.uppercase(java.util.Locale.ROOT) ?: ""
                            inputStr = charTyped
                            
                            if (charTyped.isNotEmpty()) {
                                val mapping = hotkeyMap[charTyped]
                                if (mapping != null) {
                                    val (tool, target) = mapping
                                    activeTool = tool
                                    if (tool == WorkspaceTool.PLACE_COMPONENT) {
                                        val compType = try {
                                            ComponentType.valueOf(target.uppercase(java.util.Locale.ROOT))
                                        } catch (e: Exception) {
                                            null
                                        }
                                        if (compType != null) {
                                            placingComponentType = compType
                                            placingComponentValue = null
                                            Toast.makeText(context, "Selected: ${compType.name} (Hotkey $charTyped)", Toast.LENGTH_SHORT).show()
                                        } else {
                                            placingComponentType = ComponentType.SUBCIRCUIT
                                            placingComponentValue = target
                                            Toast.makeText(context, "Selected: $target subcircuit (Hotkey $charTyped)", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        placingComponentValue = null
                                        Toast.makeText(context, "Tool Activated: ${tool.name} (Hotkey $charTyped)", Toast.LENGTH_SHORT).show()
                                    }
                                    showHotkeyOverlay = false
                                    keyboardController?.hide()
                                } else {
                                    Toast.makeText(context, "No hotkey mapped for $charTyped", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .testTag("hotkey_input_field"),
                        placeholder = { Text("Type a matching key...") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { 
                                showKeysList = !showKeysList 
                                if (showKeysList) showEditArea = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (showKeysList) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = if (showKeysList) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            modifier = Modifier.weight(1f).testTag("show_keys_toggle")
                        ) {
                            Text(if (showKeysList) "Hide Keys" else "Show Keys")
                        }

                        Button(
                            onClick = { 
                                showEditArea = !showEditArea 
                                if (showEditArea) showKeysList = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (showEditArea) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = if (showEditArea) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            modifier = Modifier.weight(1f).testTag("edit_keys_toggle")
                        ) {
                            Text(if (showEditArea) "Close Edit" else "Edit Keys")
                        }
                    }

                    if (showKeysList) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .verticalScroll(rememberScrollState())
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val sortedMap = hotkeyMap.toList().sortedBy { it.first }
                            sortedMap.chunked(2).forEach { pair ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    pair.forEach { (key, mapping) ->
                                        val (tool, target) = mapping
                                        val displayName = if (tool == WorkspaceTool.PLACE_COMPONENT) target else tool.name
                                        Row(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(vertical = 2.dp)
                                                .clickable {
                                                    activeTool = tool
                                                    if (tool == WorkspaceTool.PLACE_COMPONENT) {
                                                        val compType = try {
                                                            ComponentType.valueOf(target.uppercase(java.util.Locale.ROOT))
                                                        } catch (e: Exception) {
                                                            null
                                                        }
                                                        if (compType != null) {
                                                            placingComponentType = compType
                                                            placingComponentValue = null
                                                        } else {
                                                            placingComponentType = ComponentType.SUBCIRCUIT
                                                            placingComponentValue = target
                                                        }
                                                    } else {
                                                        placingComponentValue = null
                                                    }
                                                    showHotkeyOverlay = false
                                                    keyboardController?.hide()
                                                    Toast.makeText(context, "Selected: $displayName", Toast.LENGTH_SHORT).show()
                                                },
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = key,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = displayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                    if (pair.size < 2) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                    if (showEditArea) {
                        Spacer(modifier = Modifier.height(12.dp))
                        var editKeyName by remember { mutableStateOf("") }
                        var editTargetName by remember { mutableStateOf("") }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .verticalScroll(rememberScrollState())
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Customize Key Binding",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )

                            OutlinedTextField(
                                value = editKeyName,
                                onValueChange = { editKeyName = it.take(1).uppercase(java.util.Locale.ROOT) },
                                label = { Text("Shortcut Key (e.g. K, Y)") },
                                modifier = Modifier.fillMaxWidth().testTag("edit_key_input"),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = editTargetName,
                                onValueChange = { editTargetName = it },
                                label = { Text("Component/Tool (e.g. RESISTOR)") },
                                placeholder = { Text("RESISTOR, filter, WIRE") },
                                modifier = Modifier.fillMaxWidth().testTag("edit_target_input"),
                                singleLine = true
                            )

                            val subcircuitsCount = SubcircuitRegistry.templates.keys.size
                            Text(
                                text = "Options:\n" +
                                       "• Standard: RESISTOR, CAPACITOR, INDUCTOR, DIODE, VOLTAGE_SOURCE, CURRENT_SOURCE, GROUND, TRANSISTOR_NPN, MOSFET_N, OPAMP, THYRISTOR, RELAY, TRIAC, PORT\n" +
                                       "• Tools: WIRE, ERASE, PROBE, SELECT\n" +
                                       "• Custom Subcircuits (${subcircuitsCount}): " + 
                                       (if(subcircuitsCount == 0) "None yet" else SubcircuitRegistry.templates.keys.joinToString(", ")),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Button(
                                onClick = {
                                    val trimmedKey = editKeyName.trim().uppercase(java.util.Locale.ROOT)
                                    val trimmedTarget = editTargetName.trim()
                                    if (trimmedKey.isEmpty() || trimmedTarget.isEmpty()) {
                                        Toast.makeText(context, "Please fill in all fields!", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }

                                    // Verify tools
                                    val matchedTool = when (trimmedTarget.uppercase(java.util.Locale.ROOT)) {
                                        "WIRE" -> WorkspaceTool.DRAW_WIRE
                                        "ERASE", "DELETE" -> WorkspaceTool.ERASE
                                        "PROBE" -> WorkspaceTool.PROBE
                                        "SELECT", "POINTER" -> WorkspaceTool.SELECT
                                        else -> null
                                    }

                                    if (matchedTool != null) {
                                        hotkeyMap[trimmedKey] = matchedTool to ""
                                        Toast.makeText(context, "Mapped '$trimmedKey' to tool ${matchedTool.name}", Toast.LENGTH_SHORT).show()
                                        showEditArea = false
                                    } else {
                                        // Verify standard types
                                        val matchedStandardType = ComponentType.values().find { 
                                            it.name.equals(trimmedTarget, ignoreCase = true) 
                                        }

                                        // Verify custom subcircuits
                                        val matchedSubcircuitName = SubcircuitRegistry.templates.keys.find {
                                            it.equals(trimmedTarget, ignoreCase = true)
                                        }

                                        if (matchedStandardType != null) {
                                            hotkeyMap[trimmedKey] = WorkspaceTool.PLACE_COMPONENT to matchedStandardType.name
                                            Toast.makeText(context, "Mapped '$trimmedKey' to ${matchedStandardType.name}", Toast.LENGTH_SHORT).show()
                                            showEditArea = false
                                        } else if (matchedSubcircuitName != null) {
                                            hotkeyMap[trimmedKey] = WorkspaceTool.PLACE_COMPONENT to matchedSubcircuitName
                                            Toast.makeText(context, "Mapped '$trimmedKey' to subcircuit '$matchedSubcircuitName'", Toast.LENGTH_SHORT).show()
                                            showEditArea = false
                                        } else {
                                            Toast.makeText(
                                                context, 
                                                "Error: '$trimmedTarget' is not an existing component type or custom subcircuit!", 
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().testTag("add_hotkey_button")
                            ) {
                                Text("Save Mapping")
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { 
                    showHotkeyOverlay = false 
                    keyboardController?.hide()
                }) {
                    Text("Dismiss")
                }
            }
        )
    }
}

@Composable
fun ModeToolbar(
    activeTool: WorkspaceTool,
    onToolChange: (WorkspaceTool) -> Unit,
    selectedComponent: Component?,
    onRotate: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onCopy: (() -> Unit)? = null,
    onHotkeyClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Select Tool
            IconButton(
                onClick = { onToolChange(WorkspaceTool.SELECT) },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (activeTool == WorkspaceTool.SELECT) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                )
            ) {
                Icon(Icons.Default.Check, contentDescription = "Select pointer")
            }

            // Draw wire tool
            IconButton(
                onClick = { onToolChange(WorkspaceTool.DRAW_WIRE) },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (activeTool == WorkspaceTool.DRAW_WIRE) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                )
            ) {
                Icon(Icons.Default.Create, contentDescription = "Draw electrical wire")
            }

            // Eraser Tool
            IconButton(
                onClick = { onToolChange(WorkspaceTool.ERASE) },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (activeTool == WorkspaceTool.ERASE) MaterialTheme.colorScheme.errorContainer else Color.Transparent
                )
            ) {
                Icon(Icons.Default.Clear, contentDescription = "Delete item", tint = if (activeTool == WorkspaceTool.ERASE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }

            // Probe tool
            IconButton(
                onClick = { onToolChange(WorkspaceTool.PROBE) },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (activeTool == WorkspaceTool.PROBE) MaterialTheme.colorScheme.tertiaryContainer else Color.Transparent
                )
            ) {
                Icon(Icons.Default.Search, contentDescription = "Diagnostics Oscilloscope Probe")
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Keycap "K" Button
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .border(BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(6.dp))
                    .clickable { onHotkeyClick() }
                    .testTag("hotkey_trigger_k"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "K",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Vertical divider for Undo / Redo grouping
            Spacer(modifier = Modifier.width(4.dp))
            Box(modifier = Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Spacer(modifier = Modifier.width(4.dp))

            // Undo icon button
            IconButton(
                onClick = onUndo,
                enabled = canUndo
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Undo previous action",
                    tint = if (canUndo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                )
            }

            // Redo icon button
            IconButton(
                onClick = onRedo,
                enabled = canRedo
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Redo undone action",
                    tint = if (canRedo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                )
            }
        }

        // Active element modifications (if something selected)
        if (selectedComponent != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Rotate button
                OutlinedButton(
                    onClick = onRotate,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Rotate component", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Rotate", fontSize = 11.sp)
                }

                // Edit properties button
                Button(
                    onClick = onEdit,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit values", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Edit", fontSize = 11.sp)
                }

                if (onCopy != null) {
                    OutlinedButton(
                        onClick = onCopy,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Duplicate component", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy", fontSize = 11.sp)
                    }
                }

                // Trash button
                IconButton(
                    onClick = onDelete,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Trash component", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
fun SchematicFileBar(
    onImportClick: () -> Unit,
    onExportClick: () -> Unit,
    onRegisterSubcircuitClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .padding(vertical = 4.dp, horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("File / IC Tools:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        
        Button(
            onClick = onImportClick,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.height(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Import schema string", modifier = Modifier.size(12.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Import Blueprint", fontSize = 10.sp)
        }

        Button(
            onClick = onExportClick,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.height(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
        ) {
            Icon(Icons.Default.Share, contentDescription = "Export schema string", modifier = Modifier.size(12.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Export Blueprint", fontSize = 10.sp)
        }

        Button(
            onClick = onRegisterSubcircuitClick,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.height(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer)
        ) {
            Icon(Icons.Default.Build, contentDescription = "Make IC subcircuit", modifier = Modifier.size(12.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Build IC Block", fontSize = 10.sp)
        }
    }
}

@Composable
fun TemplatesBar(
    onLoadTemplate: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(vertical = 6.dp, horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Loaded Presets:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        
        OutlinedButton(
            onClick = { onLoadTemplate("RC") },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.height(28.dp)
        ) {
            Text("RC charging", fontSize = 11.sp)
        }

        OutlinedButton(
            onClick = { onLoadTemplate("Rectifier") },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.height(28.dp)
        ) {
            Text("Diode Rectification", fontSize = 11.sp)
        }

        OutlinedButton(
            onClick = { onLoadTemplate("RLC") },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.height(28.dp)
        ) {
            Text("RLC Bandpass", fontSize = 11.sp)
        }
    }
}

@Composable
fun ComponentCatalog(
    selectedType: ComponentType,
    onSelectType: (ComponentType) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val items = ComponentType.values().filter { type ->
        val nameMatch = when (type) {
            ComponentType.RESISTOR -> "Resistor (R) resistance ohm"
            ComponentType.CAPACITOR -> "Capacitor (C) capacitance farad"
            ComponentType.INDUCTOR -> "Inductor (L) inductance henry winding"
            ComponentType.DIODE -> "Silicon Diode (D) semiconductor silicon pn"
            ComponentType.VOLTAGE_SOURCE -> "Voltage Source (V) ac dc pulse sine sinusoidal"
            ComponentType.CURRENT_SOURCE -> "Current Source (I) ideal"
            ComponentType.GROUND -> "Ground Reference (GND) zero node 0 gnd reference"
            ComponentType.TRANSISTOR_NPN -> "NPN Bipolar Transistor (Q) bjt amplifier switch"
            ComponentType.MOSFET_N -> "N-Ch MOSFET (M) fet gate switch"
            ComponentType.THYRISTOR -> "SCR Thyristor (SCR) silicon controlled rectifier latched latch"
            ComponentType.RELAY -> "Galvanic Relay (RL) coil galvanic electromechanical switch"
            ComponentType.TRIAC -> "TRIAC Switch (TR) bidirectional switch ac high power triode"
            ComponentType.OPAMP -> "Operational Amp (U) ua741 op-amp opamp operational ic differential gain"
            ComponentType.SUBCIRCUIT -> "Hierarchical Subcircuit (X) modular nested ic block template"
            ComponentType.PORT -> "Subcircuit Port (P) interface pin terminal boundary"
        }
        nameMatch.contains(searchQuery, ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        // Component Search Input Field (sits at absolute top of body container)
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Filter components... e.g. 'npn', 'relay'", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .focusRequester(focusRequester),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
        )
        Spacer(modifier = Modifier.height(4.dp))

        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items.forEach { type ->
                val isSelected = selectedType == type
                val name = when (type) {
                    ComponentType.RESISTOR -> "Resistor (R)"
                    ComponentType.CAPACITOR -> "Capacitor (C)"
                    ComponentType.INDUCTOR -> "Inductor (L)"
                    ComponentType.DIODE -> "Silicon Diode (D)"
                    ComponentType.VOLTAGE_SOURCE -> "Voltage Source (V)"
                    ComponentType.CURRENT_SOURCE -> "Current Source (I)"
                    ComponentType.GROUND -> "Ground Reference (GND)"
                    ComponentType.TRANSISTOR_NPN -> "NPN Bipolar Transistor (Q)"
                    ComponentType.MOSFET_N -> "N-Ch MOSFET (M)"
                    ComponentType.THYRISTOR -> "SCR Thyristor (SCR)"
                    ComponentType.RELAY -> "Galvanic Relay (RL)"
                    ComponentType.TRIAC -> "TRIAC Switch (TR)"
                    ComponentType.OPAMP -> "Operational Amp (U)"
                    ComponentType.SUBCIRCUIT -> "Hierarchical Subcircuit (X)"
                    ComponentType.PORT -> "Subcircuit Port (P)"
                }
                val desc = when (type) {
                    ComponentType.RESISTOR -> "Imposed linear resistance (Ohms)"
                    ComponentType.CAPACITOR -> "Reactive filtering capacitive element"
                    ComponentType.INDUCTOR -> "Phase shifting inductive loop winding"
                    ComponentType.DIODE -> "One-way non-linear semiconductor valve"
                    ComponentType.VOLTAGE_SOURCE -> "AC Sinusoidal, Pulse, or DC potential source"
                    ComponentType.CURRENT_SOURCE -> "Ideal branch current source"
                    ComponentType.GROUND -> "Absolute Reference Node 0 (Mandatory for circuit solvers)"
                    ComponentType.TRANSISTOR_NPN -> "Active bipolar switch and semiconductor amplifier"
                    ComponentType.MOSFET_N -> "Voltage-controlled switch/amplifier"
                    ComponentType.THYRISTOR -> "Latching silicon controlled power valve"
                    ComponentType.RELAY -> "Electromagnetic isolated mechanical coil switch"
                    ComponentType.TRIAC -> "Bidirectional AC triode switch for high power control"
                    ComponentType.OPAMP -> "Operational amplifier voltage gain block"
                    ComponentType.SUBCIRCUIT -> "Modular nested block (placed in IC category)"
                    ComponentType.PORT -> "Boundary connection node terminal within nested subcircuits"
                }

                Surface(
                    onClick = { onSelectType(type) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(text = desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SimulationSettingsControl(
    settings: SimulationSettings,
    onSettingsChange: (SimulationSettings) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Simulation Command (.CMD)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        // Navigation Chips for SimType
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                SimType.TRANSIENT to "Transient (.TRAN)",
                SimType.AC to "AC Sweep (.AC)",
                SimType.OP to "DC Bias (.OP)",
                SimType.DC_SWEEP to "DC Sweep (.DC)"
            ).forEach { (type, label) ->
                val isSelected = settings.type == type
                FilterChip(
                    selected = isSelected,
                    onClick = { onSettingsChange(settings.copy(type = type)) },
                    label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        HorizontalDivider()

        when (settings.type) {
            SimType.TRANSIENT -> {
                Text("Transient Grid Intervals", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("Setup intervals for the transient network evaluation solver.", fontSize = 11.sp, color = Color.Gray)

                OutlinedTextField(
                    value = settings.stopTimeStr,
                    onValueChange = { onSettingsChange(settings.copy(stopTimeStr = it)) },
                    label = { Text("Simulation Stop Time") },
                    placeholder = { Text("e.g. 10m, 1, 100u") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = settings.stepTimeStr,
                    onValueChange = { onSettingsChange(settings.copy(stepTimeStr = it)) },
                    label = { Text("Incremental Step Size") },
                    placeholder = { Text("e.g. 0.1m, 10u") },
                    singleLine = true,
                    supportingText = {
                        Text("Controls waveform accuracy. Excessively small steps may extend runtimes.")
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            SimType.AC -> {
                Text("AC Small-Signal Sweep", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("Compute steady-state micro-signal frequency response of the network.", fontSize = 11.sp, color = Color.Gray)

                OutlinedTextField(
                    value = settings.acStartFreqStr,
                    onValueChange = { onSettingsChange(settings.copy(acStartFreqStr = it)) },
                    label = { Text("Start Frequency (Hz)") },
                    placeholder = { Text("e.g. 10, 100") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = settings.acStopFreqStr,
                    onValueChange = { onSettingsChange(settings.copy(acStopFreqStr = it)) },
                    label = { Text("Stop Frequency (Hz)") },
                    placeholder = { Text("e.g. 100k, 1meg") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = settings.acPointsCount.toString(),
                    onValueChange = { 
                        val parsed = it.toIntOrNull() ?: 100
                        onSettingsChange(settings.copy(acPointsCount = parsed))
                    },
                    label = { Text("Number of Sweep Points") },
                    placeholder = { Text("e.g. 100") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            SimType.OP -> {
                Text("DC Operating Point Analysis (.OP)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Calculates static DC steady-state node bias:\n• Capacitors are treated as open-circuits.\n• Inductors are treated as short-circuits.\n• Solved operating voltages are returned as flat reference channels on the graph.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
            SimType.DC_SWEEP -> {
                Text("DC Sweep Parameters", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("Sweeps a designated voltage/current source over a linear span.", fontSize = 11.sp, color = Color.Gray)

                OutlinedTextField(
                    value = settings.sweepSource,
                    onValueChange = { onSettingsChange(settings.copy(sweepSource = it)) },
                    label = { Text("Source ID to Sweep") },
                    placeholder = { Text("e.g. V1, I1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = settings.sweepStart.toString(),
                        onValueChange = { 
                            val parsed = it.toDoubleOrNull() ?: 0.0
                            onSettingsChange(settings.copy(sweepStart = parsed))
                        },
                        label = { Text("Start Volt [V]") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = settings.sweepStop.toString(),
                        onValueChange = { 
                            val parsed = it.toDoubleOrNull() ?: 10.0
                            onSettingsChange(settings.copy(sweepStop = parsed))
                        },
                        label = { Text("Stop Volt [V]") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = settings.sweepStep.toString(),
                    onValueChange = { 
                        val parsed = it.toDoubleOrNull() ?: 0.5
                        onSettingsChange(settings.copy(sweepStep = parsed))
                    },
                    label = { Text("Sweep Increment Step") },
                    placeholder = { Text("e.g. 0.5") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        HorizontalDivider()

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("SPICE Cheat Sheet Commands", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("• 1m = 10^-3 (milli)\n• 1u = 10^-6 (micro)\n• 1n = 10^-9 (nano)\n• 1meg = 10^6 (Mega)\n• GND node must exist (0) or error occurs.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// --- INITIAL TEMPLATES CREATOR HELPERS ---

fun loadRCTemplate(components: MutableList<Component>, wires: MutableList<Wire>) {
    // RC Charger setup
    components.add(Component("V1", ComponentType.VOLTAGE_SOURCE, "V1", "PULSE(0 5 0 1u 1u 5m 10m)", 4, 8, Orientation.DEG_90))
    components.add(Component("GND1", ComponentType.GROUND, "GND", "0", 4, 9, Orientation.DEG_0))
    components.add(Component("R1", ComponentType.RESISTOR, "R1", "1k", 7, 8, Orientation.DEG_0))
    components.add(Component("C1", ComponentType.CAPACITOR, "C1", "1u", 10, 9, Orientation.DEG_90))
    components.add(Component("GND2", ComponentType.GROUND, "GND", "0", 10, 10, Orientation.DEG_0))

    // Connections
    wires.add(Wire("W1", GridPoint(4, 7), GridPoint(6, 7))) // Connect Voltage V(+) to R1 Left Pin
    wires.add(Wire("W2", GridPoint(6, 7), GridPoint(6, 8))) // vertical jumper
    wires.add(Wire("W3", GridPoint(8, 8), GridPoint(10, 8))) // Connect R1-Right to C1-Top (which holds Node N2)
}

fun loadRectifierTemplate(components: MutableList<Component>, wires: MutableList<Wire>) {
    // Diode Half Wave rectifier with optional filtering capacitor
    components.add(Component("V1", ComponentType.VOLTAGE_SOURCE, "V1", "SINE(0 10 100)", 4, 8, Orientation.DEG_90))
    components.add(Component("GND1", ComponentType.GROUND, "GND", "0", 4, 9, Orientation.DEG_0))
    
    components.add(Component("D1", ComponentType.DIODE, "D1", "D1N4148", 7, 8, Orientation.DEG_0))
    
    // Load resistor
    components.add(Component("R1", ComponentType.RESISTOR, "RL", "100", 10, 9, Orientation.DEG_90))
    components.add(Component("GND2", ComponentType.GROUND, "GND", "0", 10, 10, Orientation.DEG_0))
    
    // Smoothing capacitor
    components.add(Component("C1", ComponentType.CAPACITOR, "C_filt", "10u", 13, 9, Orientation.DEG_90))
    components.add(Component("GND3", ComponentType.GROUND, "GND", "0", 13, 10, Orientation.DEG_0))

    // Connections
    wires.add(Wire("W1", GridPoint(4, 7), GridPoint(6, 7)))
    wires.add(Wire("W2", GridPoint(6, 7), GridPoint(6, 8)))
    wires.add(Wire("W3", GridPoint(8, 8), GridPoint(10, 8))) // Diode to R_load
    wires.add(Wire("W4", GridPoint(10, 8), GridPoint(13, 8))) // Load top to cap filter top
}

fun loadRLCTemplate(components: MutableList<Component>, wires: MutableList<Wire>) {
    // RLC Resonant filter SINE input
    components.add(Component("V1", ComponentType.VOLTAGE_SOURCE, "V1", "SINE(0 10 500)", 4, 8, Orientation.DEG_90))
    components.add(Component("GND1", ComponentType.GROUND, "GND", "0", 4, 9, Orientation.DEG_0))

    components.add(Component("R1", ComponentType.RESISTOR, "R1", "10", 7, 8, Orientation.DEG_0))
    components.add(Component("L1", ComponentType.INDUCTOR, "L1", "10m", 11, 8, Orientation.DEG_0))
    
    components.add(Component("C1", ComponentType.CAPACITOR, "C1", "10u", 14, 9, Orientation.DEG_90))
    components.add(Component("GND2", ComponentType.GROUND, "GND", "0", 14, 10, Orientation.DEG_0))

    // Wires
    wires.add(Wire("W1", GridPoint(4, 7), GridPoint(6, 7)))
    wires.add(Wire("W2", GridPoint(6, 7), GridPoint(6, 8)))
    wires.add(Wire("W3", GridPoint(8, 8), GridPoint(10, 8))) // R1 to L1
    wires.add(Wire("W4", GridPoint(12, 8), GridPoint(14, 8))) // L1 to C1 Top
}

