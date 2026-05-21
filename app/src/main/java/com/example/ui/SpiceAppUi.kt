package com.example.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.*
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpiceAppUi() {
    val context = LocalContext.current

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
    
    // Dialogs
    var showPropertiesDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showImportExportDialog by remember { mutableStateOf(false) }
    var showSubcircuitDialog by remember { mutableStateOf(false) }

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
        loadRCTemplate(components, wires)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            "AE Spice Studio",
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.SansSerif,
                            letterSpacing = 1.sp
                        )
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

                    // Create reusable modular IC Template
                    OutlinedButton(
                        onClick = { showSubcircuitDialog = true },
                        modifier = Modifier.padding(end = 6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.AddCircle, contentDescription = "Create IC", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Create IC", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    // Import / Export JSON Schema blueprints
                    OutlinedButton(
                        onClick = {
                            importText = exportToSchemaString()
                            showImportExportDialog = true
                        },
                        modifier = Modifier.padding(end = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Import/Export blueprints", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("I/O", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    // Run simulation fab
                    Button(
                        onClick = { runSpiceSimulation() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFD1E4FF), // Elegant Dark Primary Accent Blue
                            contentColor = Color(0xFF00315C) // Contrast dark blue
                        ),
                        modifier = Modifier.padding(end = 8.dp)
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
            val isTablet = maxWidth > 840.dp
            
            if (isTablet) {
                // --- TABLET MASTER-DETAIL DOCK LAYOUT ---
                Row(modifier = Modifier.fillMaxSize()) {
                    // Left Workspace editor (60% width)
                    Box(modifier = Modifier.weight(1.3f).fillMaxHeight()) {
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
                            onProbeNode = { handleProbeTapped(it) }
                        )
                    }

                    VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    // Right diagnostics / controls column
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        // Diagnostics template presets
                        TemplatesBar(
                            onLoadTemplate = { name ->
                                selectedComponent = null
                                simResult = null
                                components.clear()
                                wires.clear()
                                when (name) {
                                    "RC" -> loadRCTemplate(components, wires)
                                    "Rectifier" -> loadRectifierTemplate(components, wires)
                                    "RLC" -> loadRLCTemplate(components, wires)
                                }
                            }
                        )

                        SchematicFileBar(
                            onImportClick = {
                                importText = ""
                                showImportExportDialog = true
                            },
                            onExportClick = {
                                importText = exportToSchemaString()
                                showImportExportDialog = true
                            },
                            onRegisterSubcircuitClick = {
                                subcircuitNameInput = "MY_IC_${(10..99).random()}"
                                showSubcircuitDialog = true
                            }
                        )

                        // Mode Selector / Toolbar
                        ModeToolbar(
                            activeTool = activeTool,
                            onToolChange = { activeTool = it },
                            selectedComponent = selectedComponent,
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
                                    }
                                }
                            },
                            onDelete = {
                                selectedComponent?.let { comp ->
                                    pushUndoState()
                                    components.remove(comp)
                                    selectedComponent = null
                                }
                            },
                            onEdit = { showPropertiesDialog = true },
                            onUndo = { undo() },
                            onRedo = { redo() },
                            canUndo = undoStack.isNotEmpty(),
                            canRedo = redoStack.isNotEmpty(),
                            onCopy = { duplicateSelectedComponent() }
                        )

                        Divider()

                        // Component / Controls selector tabs
                        TabRow(selectedTabIndex = bottomTabState) {
                            Tab(selected = bottomTabState == 0, onClick = { bottomTabState = 0 }) {
                                Text("Wave Plotter", modifier = Modifier.padding(12.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Tab(selected = bottomTabState == 1, onClick = { bottomTabState = 1 }) {
                                Text("Components", modifier = Modifier.padding(12.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Tab(selected = bottomTabState == 2, onClick = { bottomTabState = 2 }) {
                                Text("Sim Commands", modifier = Modifier.padding(12.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Tab(selected = bottomTabState == 3, onClick = { bottomTabState = 3 }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        tint = if (bottomTabState == 3) Color(0xFFFBBC05) else Color.Gray,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Gemini AI", modifier = Modifier.padding(12.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            when (bottomTabState) {
                                0 -> {
                                    // Plotter display
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
                                1 -> {
                                    // Catalog components list
                                    ComponentCatalog(
                                        selectedType = placingComponentType,
                                        onSelectType = {
                                            placingComponentType = it
                                            activeTool = WorkspaceTool.PLACE_COMPONENT
                                        }
                                    )
                                }
                                2 -> {
                                    // Settings config
                                    SimulationSettingsControl(
                                        settings = simSettings,
                                        onSettingsChange = { simSettings = it }
                                    )
                                }
                                3 -> {
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
                            }
                        }
                    }
                }
            } else {
                // --- PHONE ADAPTIVE LAYOUT (TOP / BOTTOM COEXIST) ---
                Column(modifier = Modifier.fillMaxSize()) {
                    // Top 45% workspace
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                            onProbeNode = { handleProbeTapped(it) }
                        )

                        // Floating auxiliary template preset trigger
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    components.clear()
                                    wires.clear()
                                    simResult = null
                                    loadRCTemplate(components, wires)
                                },
                                colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Load RC Example", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    // Bottom diagnostics detail column (55% height)
                    Column(
                        modifier = Modifier
                            .weight(1.3f)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        // Miniature dynamic toolbar
                        ModeToolbar(
                            activeTool = activeTool,
                            onToolChange = { activeTool = it },
                            selectedComponent = selectedComponent,
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
                                    }
                                }
                            },
                            onDelete = {
                                selectedComponent?.let { comp ->
                                    pushUndoState()
                                    components.remove(comp)
                                    selectedComponent = null
                                }
                            },
                            onEdit = { showPropertiesDialog = true },
                            onUndo = { undo() },
                            onRedo = { redo() },
                            canUndo = undoStack.isNotEmpty(),
                            canRedo = redoStack.isNotEmpty(),
                            onCopy = { duplicateSelectedComponent() }
                        )

                        Divider()

                        // Action selectors tabs for phone
                        TabRow(selectedTabIndex = bottomTabState) {
                            Tab(selected = bottomTabState == 0, onClick = { bottomTabState = 0 }) {
                                Text("Wave Viewer", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
                            }
                            Tab(selected = bottomTabState == 1, onClick = { bottomTabState = 1 }) {
                                Text("Add Parts", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
                            }
                            Tab(selected = bottomTabState == 2, onClick = { bottomTabState = 2 }) {
                                Text("Command Run", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
                            }
                            Tab(selected = bottomTabState == 3, onClick = { bottomTabState = 3 }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        tint = if (bottomTabState == 3) Color(0xFFFBBC05) else Color.Gray,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("Gemini AI", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
                                }
                            }
                        }

                        // Load templates card inside control board
                        TemplatesBar(
                            onLoadTemplate = { name ->
                                selectedComponent = null
                                simResult = null
                                components.clear()
                                wires.clear()
                                when (name) {
                                    "RC" -> loadRCTemplate(components, wires)
                                    "Rectifier" -> loadRectifierTemplate(components, wires)
                                    "RLC" -> loadRLCTemplate(components, wires)
                                }
                            }
                        )

                        SchematicFileBar(
                            onImportClick = {
                                importText = ""
                                showImportExportDialog = true
                            },
                            onExportClick = {
                                importText = exportToSchemaString()
                                showImportExportDialog = true
                            },
                            onRegisterSubcircuitClick = {
                                subcircuitNameInput = "MY_IC_${(10..99).random()}"
                                showSubcircuitDialog = true
                            }
                        )

                        Box(modifier = Modifier.weight(1f)) {
                            when (bottomTabState) {
                                0 -> {
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
                                1 -> {
                                    ComponentCatalog(
                                        selectedType = placingComponentType,
                                        onSelectType = {
                                            placingComponentType = it
                                            activeTool = WorkspaceTool.PLACE_COMPONENT
                                        }
                                    )
                                }
                                2 -> {
                                    SimulationSettingsControl(
                                        settings = simSettings,
                                        onSettingsChange = { simSettings = it }
                                    )
                                }
                                3 -> {
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
                }
                showPropertiesDialog = false
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
    onCopy: (() -> Unit)? = null
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
                    imageVector = Icons.Default.KeyboardArrowLeft,
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
                    imageVector = Icons.Default.KeyboardArrowRight,
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
            .padding(12.dp)
    ) {
        Text("Component Box Selector", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text("Choose structural part below, then tap empty grid cell to place it.", fontSize = 11.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(6.dp))

        // Component Search Input Field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Filter components... e.g. 'npn', 'relay'", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
        )
        Spacer(modifier = Modifier.height(6.dp))

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

        Divider()

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

        Divider()

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

