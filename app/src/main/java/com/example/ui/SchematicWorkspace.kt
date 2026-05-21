package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.Component
import com.example.engine.ComponentType
import com.example.engine.GridPoint
import com.example.engine.Orientation
import com.example.engine.SimResult
import com.example.engine.Wire
import kotlin.math.abs
import kotlin.math.roundToInt

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerInputChange

enum class WorkspaceTool {
    SELECT,
    PLACE_COMPONENT,
    DRAW_WIRE,
    ERASE,
    PROBE
}

@Composable
fun SchematicWorkspace(
    components: List<Component>,
    wires: List<Wire>,
    selectedComponent: Component?,
    activeTool: WorkspaceTool,
    placingType: ComponentType,
    simResult: SimResult?, // Available when simulation finished
    onSelectComponent: (Component?) -> Unit,
    onAddComponent: (Component) -> Unit,
    onAddWire: (Wire) -> Unit,
    onDeleteComponent: (Component) -> Unit,
    onDeleteWire: (Wire) -> Unit,
    onProbeNode: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Grid settings
    val gridSize = 40f // dp-adjacent sizes
    
    // Viewport panning offset
    var scrollOffsetX by remember { mutableStateOf(0f) }
    var scrollOffsetY by remember { mutableStateOf(0f) }

    // Wire placement state
    var wireStartPoint by remember { mutableStateOf<GridPoint?>(null) }
    var hoverGridPoint by remember { mutableStateOf<GridPoint?>(null) }

    // Updated states to avoid recreation of high-frequency pointerInput blocks
    val currentActiveTool by rememberUpdatedState(activeTool)
    val currentPlacingType by rememberUpdatedState(placingType)
    val currentComponents by rememberUpdatedState(components)
    val currentWires by rememberUpdatedState(wires)
    val currentSimResult by rememberUpdatedState(simResult)
    val currentScrollOffsetX by rememberUpdatedState(scrollOffsetX)
    val currentScrollOffsetY by rememberUpdatedState(scrollOffsetY)

    val currentOnSelectComponent by rememberUpdatedState(onSelectComponent)
    val currentOnAddComponent by rememberUpdatedState(onAddComponent)
    val currentOnAddWire by rememberUpdatedState(onAddWire)
    val currentOnDeleteComponent by rememberUpdatedState(onDeleteComponent)
    val currentOnDeleteWire by rememberUpdatedState(onDeleteWire)
    val currentOnProbeNode by rememberUpdatedState(onProbeNode)

    // Convert pixel inputs to Grid Coordinates
    fun pxToGrid(pxX: Float, pxY: Float): GridPoint {
        val gridX = ((pxX - scrollOffsetX) / gridSize).roundToInt()
        val gridY = ((pxY - scrollOffsetY) / gridSize).roundToInt()
        return GridPoint(gridX, gridY)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1A1C1E)) // Elegant Dark Background
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            if (currentActiveTool == WorkspaceTool.SELECT) {
                                change.consume()
                                scrollOffsetX += dragAmount.x
                                scrollOffsetY += dragAmount.y
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            val gridPt = GridPoint(
                                ((offset.x - scrollOffsetX) / gridSize).roundToInt(),
                                ((offset.y - scrollOffsetY) / gridSize).roundToInt()
                            )
                            if (currentActiveTool == WorkspaceTool.SELECT) {
                                val tappedComp = currentComponents.find { comp ->
                                    val dist = abs(comp.gridX - gridPt.x) + abs(comp.gridY - gridPt.y)
                                    dist <= 1
                                }
                                currentOnSelectComponent(tappedComp)
                            } else {
                                when (currentActiveTool) {
                                    WorkspaceTool.PLACE_COMPONENT -> {
                                        val sameTypeCount = currentComponents.filter { it.type == currentPlacingType }.size + 1
                                        val prefix = when (currentPlacingType) {
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
                                        val defaultValue = when (currentPlacingType) {
                                            ComponentType.RESISTOR -> "1k"
                                            ComponentType.CAPACITOR -> "10u"
                                            ComponentType.INDUCTOR -> "1m"
                                            ComponentType.DIODE -> "D1N4148"
                                            ComponentType.VOLTAGE_SOURCE -> "SINE(0 10 1k)"
                                            ComponentType.CURRENT_SOURCE -> "1m"
                                            ComponentType.GROUND -> "0"
                                            ComponentType.TRANSISTOR_NPN -> "BC547"
                                            ComponentType.MOSFET_N -> "IRF540"
                                            ComponentType.THYRISTOR -> "MCR100"
                                            ComponentType.RELAY -> "5V Relay"
                                            ComponentType.TRIAC -> "BT136"
                                            ComponentType.OPAMP -> "UA741"
                                            ComponentType.SUBCIRCUIT -> "MySubcircuit"
                                            ComponentType.PORT -> "PortA"
                                        }
                                        val id = "${prefix}_${System.currentTimeMillis()}"
                                        val comp = Component(
                                            id = id,
                                            type = currentPlacingType,
                                            name = "$prefix$sameTypeCount",
                                            valueStr = defaultValue,
                                            gridX = gridPt.x,
                                            gridY = gridPt.y
                                        )
                                        currentOnAddComponent(comp)
                                    }
                                    WorkspaceTool.DRAW_WIRE -> {
                                        val currentStart = wireStartPoint
                                        if (currentStart == null) {
                                            wireStartPoint = gridPt
                                        } else {
                                            if (currentStart != gridPt) {
                                                if (currentStart.x == gridPt.x || currentStart.y == gridPt.y) {
                                                    val wire = Wire("${System.currentTimeMillis()}", currentStart, gridPt)
                                                    currentOnAddWire(wire)
                                                } else {
                                                    val midPoint = GridPoint(gridPt.x, currentStart.y)
                                                    val wire1 = Wire("${System.currentTimeMillis()}_1", currentStart, midPoint)
                                                    val wire2 = Wire("${System.currentTimeMillis()}_2", midPoint, gridPt)
                                                    currentOnAddWire(wire1)
                                                    currentOnAddWire(wire2)
                                                }
                                            }
                                            wireStartPoint = null
                                        }
                                    }
                                    WorkspaceTool.ERASE -> {
                                        val comp = currentComponents.find { c ->
                                            abs(c.gridX - gridPt.x) + abs(c.gridY - gridPt.y) <= 1
                                        }
                                        if (comp != null) {
                                            currentOnDeleteComponent(comp)
                                        } else {
                                            val wire = currentWires.find { w -> w.contains(gridPt) }
                                            if (wire != null) {
                                                currentOnDeleteWire(wire)
                                            }
                                        }
                                    }
                                    WorkspaceTool.PROBE -> {
                                        if (currentSimResult != null) {
                                            val probedWire = currentWires.find { it.contains(gridPt) }
                                            val probedComp = currentComponents.find { c -> c.getPins().contains(gridPt) }
                                            if (probedWire != null || probedComp != null) {
                                                currentOnProbeNode(gridPt.toString())
                                            }
                                        }
                                    }
                                    else -> {}
                                }
                            }
                        }
                    )
                }
        ) {
            // Draw grid dots safely (clamping canvas boundaries to prevent infinite loops in unconstrained view constraints)
            val safeWidthPx = if (widthPx.isFinite()) widthPx.coerceIn(0f, 3000f) else 1500f
            val safeHeightPx = if (heightPx.isFinite()) heightPx.coerceIn(0f, 3000f) else 1500f

            val startGridX = (-scrollOffsetX / gridSize).toInt() - 2
            val endGridX = startGridX + (safeWidthPx / gridSize).toInt() + 4
            val startGridY = (-scrollOffsetY / gridSize).toInt() - 2
            val endGridY = startGridY + (safeHeightPx / gridSize).toInt() + 4

            for (gx in startGridX..endGridX) {
                for (gy in startGridY..endGridY) {
                    val cx = gx * gridSize + scrollOffsetX
                    val cy = gy * gridSize + scrollOffsetY
                    drawCircle(
                        color = Color(0xFF44474E), // Elegant Dark grid dots
                        radius = 2.0f,
                        center = Offset(cx, cy)
                    )
                }
            }

            // Draw Wire routing preview if drafting
            val wStart = wireStartPoint
            if (activeTool == WorkspaceTool.DRAW_WIRE && wStart != null) {
                // If hover is drawn, preview the routing lines
                // Hover coordinate computation is handled, or we fall back to a straight line.
                // We'll draw straight from start coordinate to cursor to make wiring predictable!
            }

            // Draw Wires
            wires.forEach { wire ->
                val sx = wire.start.x * gridSize + scrollOffsetX
                val sy = wire.start.y * gridSize + scrollOffsetY
                val ex = wire.end.x * gridSize + scrollOffsetX
                val ey = wire.end.y * gridSize + scrollOffsetY
                drawLine(
                    color = Color(0xFFD1E4FF), // ELEGANT ACCENT BLUE WIRES
                    start = Offset(sx, sy),
                    end = Offset(ex, ey),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )
            }

            // Draw Components
            components.forEach { comp ->
                val cx = comp.gridX * gridSize + scrollOffsetX
                val cy = comp.gridY * gridSize + scrollOffsetY
                val isCompSelected = selectedComponent?.id == comp.id

                drawComponentSymbol(
                    comp = comp,
                    cx = cx,
                    cy = cy,
                    isSelected = isCompSelected,
                    gridSize = gridSize
                )
            }

            // Draw wire nodes / junctions (connection blobs)
            // Any coordinate connected to 3 or more wires / pins gets a junction ball
            val connectionsMap = mutableMapOf<GridPoint, Int>()
            wires.forEach { w ->
                connectionsMap[w.start] = (connectionsMap[w.start] ?: 0) + 1
                connectionsMap[w.end] = (connectionsMap[w.end] ?: 0) + 1
            }
            components.forEach { c ->
                c.getPins().forEach { p ->
                    connectionsMap[p] = (connectionsMap[p] ?: 0) + 1
                }
            }
            connectionsMap.forEach { (pt, count) ->
                if (count >= 3) {
                    val cx = pt.x * gridSize + scrollOffsetX
                    val cy = pt.y * gridSize + scrollOffsetY
                    drawCircle(
                        color = Color(0xFFD1E4FF), // ELEGANT ACCENT BLUE junctions
                        radius = 5f,
                        center = Offset(cx, cy)
                    )
                }
            }

            // Draw diagnostic probe tips if simulation has run
            if (activeTool == WorkspaceTool.PROBE && simResult != null) {
                // Overlay glowing grid centers to signal they can tap there
                wires.forEach { w ->
                    val cxS = w.start.x * gridSize + scrollOffsetX
                    val cyS = w.start.y * gridSize + scrollOffsetY
                    drawCircle(
                        color = Color(0xFFFACC15).copy(alpha = 0.3f), // Glow yellow
                        radius = 8f,
                        center = Offset(cxS, cyS)
                    )
                    val cxE = w.end.x * gridSize + scrollOffsetX
                    val cyE = w.end.y * gridSize + scrollOffsetY
                    drawCircle(
                        color = Color(0xFFFACC15).copy(alpha = 0.3f),
                        radius = 8f,
                        center = Offset(cxE, cyE)
                    )
                }
            }
        }

        // --- HUD PANEL IN WORKSPACE ---
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color(0xE6141316), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF44474E), RoundedCornerShape(8.dp))
                .padding(10.dp)
        ) {
            Text(
                text = "Workspace Tool",
                style = MaterialTheme.typography.labelSmall,
                color = Color.LightGray
            )
            Text(
                text = when (activeTool) {
                    WorkspaceTool.SELECT -> "Select/Pan Mode"
                    WorkspaceTool.PLACE_COMPONENT -> "Stamp: ${placingType.name}"
                    WorkspaceTool.DRAW_WIRE -> "Wiring Route"
                    WorkspaceTool.ERASE -> "Eraser Tool"
                    WorkspaceTool.PROBE -> "Oscillating Probe 🟢"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = when (activeTool) {
                    WorkspaceTool.PROBE -> Color(0xFF4ADE80)
                    WorkspaceTool.DRAW_WIRE -> Color(0xFF06B6D4)
                    else -> MaterialTheme.colorScheme.primary
                }
            )

            if (activeTool == WorkspaceTool.DRAW_WIRE && wireStartPoint != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Tap destination node pin...",
                    fontSize = 11.sp,
                    color = Color(0xFFFACC15)
                )
            }
        }
    }
}

/**
 * Custom Canvas painter to draw highly detailed component symbols.
 */
fun DrawScope.drawComponentSymbol(
    comp: Component,
    cx: Float,
    cy: Float,
    isSelected: Boolean,
    gridSize: Float
) {
    val symbolColor = if (isSelected) Color(0xFFFFB4AB) else Color(0xFFD1E4FF) // Elegant Dark component colors
    val strokeStyle = Stroke(width = 3.5f, cap = StrokeCap.Round)

    withTransform({
        // Automatically rotate the drawing canvas relative to grid point center
        rotate(comp.orientation.degrees, Offset(cx, cy))
    }) {
        when (comp.type) {
            ComponentType.RESISTOR -> {
                // Drawing horizontal resistor relative to (cx, cy)
                val path = Path().apply {
                    moveTo(cx - 40, cy)
                    lineTo(cx - 24, cy)
                    
                    // Zigzags
                    lineTo(cx - 18, cy - 12)
                    lineTo(cx - 12, cy + 12)
                    lineTo(cx - 6, cy - 12)
                    lineTo(cx, cy + 12)
                    lineTo(cx + 6, cy - 12)
                    lineTo(cx + 12, cy + 12)
                    lineTo(cx + 18, cy - 12)
                    
                    lineTo(cx + 24, cy)
                    lineTo(cx + 40, cy)
                }
                drawPath(path = path, color = symbolColor, style = strokeStyle)
                
                // Red Terminal Pins represent connectors
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx - 40, cy))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx + 40, cy))

                // Label Text
                drawContext.canvas.nativeCanvas.drawText(
                    comp.name,
                    cx - 20,
                    cy - 22,
                    android.graphics.Paint().apply {
                        color = if (isSelected) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                        textSize = 22f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
                drawContext.canvas.nativeCanvas.drawText(
                    comp.valueStr,
                    cx - 20,
                    cy + 34,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.LTGRAY
                        textSize = 19f
                    }
                )
            }
            ComponentType.CAPACITOR -> {
                // Capacitor parallel plates
                drawLine(color = symbolColor, start = Offset(cx - 40, cy), end = Offset(cx - 7, cy), strokeWidth = 3.5f)
                drawLine(color = symbolColor, start = Offset(cx - 7, cy - 20), end = Offset(cx - 7, cy + 20), strokeWidth = 5f)
                drawLine(color = symbolColor, start = Offset(cx + 7, cy - 20), end = Offset(cx + 7, cy + 20), strokeWidth = 5f)
                drawLine(color = symbolColor, start = Offset(cx + 7, cy), end = Offset(cx + 40, cy), strokeWidth = 3.5f)

                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx - 40, cy))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx + 40, cy))

                drawContext.canvas.nativeCanvas.drawText(
                    comp.name,
                    cx - 20,
                    cy - 26,
                    android.graphics.Paint().apply {
                        color = if (isSelected) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                        textSize = 22f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
                drawContext.canvas.nativeCanvas.drawText(
                    comp.valueStr,
                    cx - 20,
                    cy + 38,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.LTGRAY
                        textSize = 19f
                    }
                )
            }
            ComponentType.INDUCTOR -> {
                // Series of solenoid curves (drawn as loops)
                val path = Path().apply {
                    moveTo(cx - 40, cy)
                    lineTo(cx - 24, cy)
                    
                    // Simple schematic coil arcs using quadratic Beziers
                    quadraticTo(cx - 18, cy - 14, cx - 12, cy)
                    quadraticTo(cx - 6, cy - 14, cx, cy)
                    quadraticTo(cx + 6, cy - 14, cx + 12, cy)
                    quadraticTo(cx + 18, cy - 14, cx + 24, cy)
                    
                    lineTo(cx + 40, cy)
                }
                drawPath(path = path, color = symbolColor, style = strokeStyle)

                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx - 40, cy))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx + 40, cy))

                drawContext.canvas.nativeCanvas.drawText(
                    comp.name,
                    cx - 20,
                    cy - 22,
                    android.graphics.Paint().apply {
                        color = if (isSelected) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                        textSize = 22f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
                drawContext.canvas.nativeCanvas.drawText(
                    comp.valueStr,
                    cx - 20,
                    cy + 30,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.LTGRAY
                        textSize = 19f
                    }
                )
            }
            ComponentType.DIODE -> {
                // Diode Anode triangle and Cathode band (Anode left, Cathode right)
                drawLine(color = symbolColor, start = Offset(cx - 40, cy), end = Offset(cx - 14, cy), strokeWidth = 3.5f)
                
                // Triangle pointing right
                val path = Path().apply {
                    moveTo(cx - 14, cy - 15)
                    lineTo(cx + 14, cy)
                    lineTo(cx - 14, cy + 15)
                    close()
                }
                drawPath(path = path, color = symbolColor, style = Stroke(width = 3.5f))
                
                // Cathode bar
                drawLine(color = symbolColor, start = Offset(cx + 14, cy - 15), end = Offset(cx + 14, cy + 15), strokeWidth = 5f)
                
                drawLine(color = symbolColor, start = Offset(cx + 14, cy), end = Offset(cx + 40, cy), strokeWidth = 3.5f)

                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx - 40, cy))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx + 40, cy))

                drawContext.canvas.nativeCanvas.drawText(
                    comp.name,
                    cx - 20,
                    cy - 24,
                    android.graphics.Paint().apply {
                        color = if (isSelected) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                        textSize = 22f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
                drawContext.canvas.nativeCanvas.drawText(
                    comp.valueStr,
                    cx - 20,
                    cy + 34,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.LTGRAY
                        textSize = 19f
                    }
                )
            }
            ComponentType.VOLTAGE_SOURCE -> {
                // Circle voltage source
                drawLine(color = symbolColor, start = Offset(cx - 40, cy), end = Offset(cx - 20, cy), strokeWidth = 3.5f)
                drawCircle(color = symbolColor, radius = 20f, center = Offset(cx, cy), style = strokeStyle)
                drawLine(color = symbolColor, start = Offset(cx + 20, cy), end = Offset(cx + 40, cy), strokeWidth = 3.5f)

                // Plus (+) and minus (-) inside circle to detail polarization
                // Positive + details (closer to left terminal)
                drawLine(color = symbolColor, start = Offset(cx - 12, cy), end = Offset(cx - 6, cy), strokeWidth = 2.5f)
                drawLine(color = symbolColor, start = Offset(cx - 9, cy - 3), end = Offset(cx - 9, cy + 3), strokeWidth = 2.5f)
                // Negative - details (closer to right terminal)
                drawLine(color = symbolColor, start = Offset(cx + 6, cy), end = Offset(cx + 12, cy), strokeWidth = 2.5f)

                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx - 40, cy))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx + 40, cy))

                drawContext.canvas.nativeCanvas.drawText(
                    comp.name,
                    cx - 22,
                    cy - 26,
                    android.graphics.Paint().apply {
                        color = if (isSelected) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                        textSize = 22f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
                
                // Show raw amplitude / config info concisely
                val shortVal = if (comp.valueStr.contains("SINE", ignoreCase = true)) "AC Sine" 
                               else if (comp.valueStr.contains("PULSE", ignoreCase = true)) "AC Pulse"
                               else comp.valueStr
                drawContext.canvas.nativeCanvas.drawText(
                    shortVal,
                    cx - 30,
                    cy + 38,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.LTGRAY
                        textSize = 18f
                    }
                )
            }
            ComponentType.CURRENT_SOURCE -> {
                // Circle current source
                drawLine(color = symbolColor, start = Offset(cx - 40, cy), end = Offset(cx - 20, cy), strokeWidth = 3.5f)
                drawCircle(color = symbolColor, radius = 20f, center = Offset(cx, cy), style = strokeStyle)
                drawLine(color = symbolColor, start = Offset(cx + 20, cy), end = Offset(cx + 40, cy), strokeWidth = 3.5f)

                // Render arrow inside the circle pointing from left to right (current direction)
                drawLine(color = symbolColor, start = Offset(cx - 10, cy), end = Offset(cx + 10, cy), strokeWidth = 3f)
                val arrowPath = Path().apply {
                    moveTo(cx + 10, cy)
                    lineTo(cx + 4, cy - 5)
                    lineTo(cx + 4, cy + 5)
                    close()
                }
                drawPath(path = arrowPath, color = symbolColor)

                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx - 40, cy))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx + 40, cy))

                drawContext.canvas.nativeCanvas.drawText(
                    comp.name,
                    cx - 20,
                    cy - 26,
                    android.graphics.Paint().apply {
                        color = if (isSelected) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                        textSize = 22f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
            }
            ComponentType.GROUND -> {
                // Ground triangle element
                drawLine(color = symbolColor, start = Offset(cx, cy), end = Offset(cx, cy + 14), strokeWidth = 3.5f)
                
                // Horizontal segments getting smaller
                drawLine(color = symbolColor, start = Offset(cx - 18, cy + 14), end = Offset(cx + 18, cy + 14), strokeWidth = 3.5f)
                drawLine(color = symbolColor, start = Offset(cx - 11, cy + 21), end = Offset(cx + 11, cy + 21), strokeWidth = 3.5f)
                drawLine(color = symbolColor, start = Offset(cx - 4, cy + 28), end = Offset(cx + 4, cy + 28), strokeWidth = 3.5f)

                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx, cy))

                drawContext.canvas.nativeCanvas.drawText(
                    "GND",
                    cx + 22,
                    cy + 22,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.LTGRAY
                        textSize = 18f
                    }
                )
            }
            ComponentType.TRANSISTOR_NPN -> {
                // Base vertical bar
                drawLine(color = symbolColor, start = Offset(cx - 10, cy - 20), end = Offset(cx - 10, cy + 20), strokeWidth = 5f)
                // Base contact wire
                drawLine(color = symbolColor, start = Offset(cx - 40, cy), end = Offset(cx - 10, cy), strokeWidth = 3.5f)
                
                // Collector wire
                val cPath = Path().apply {
                    moveTo(cx - 10, cy - 10)
                    lineTo(cx + 15, cy - 25)
                    lineTo(cx + 15, cy - 40)
                    lineTo(cx + 40, cy - 40)
                }
                drawPath(path = cPath, color = symbolColor, style = strokeStyle)

                // Emitter wire
                val ePath = Path().apply {
                    moveTo(cx - 10, cy + 10)
                    lineTo(cx + 15, cy + 25)
                    lineTo(cx + 15, cy + 40)
                    lineTo(cx + 40, cy + 40)
                }
                drawPath(path = ePath, color = symbolColor, style = strokeStyle)

                // Emitter arrow pointing down-right
                val arrowPath = Path().apply {
                    moveTo(cx + 7, cy + 21)
                    lineTo(cx + 15, cy + 25)
                    lineTo(cx + 12, cy + 16)
                    close()
                }
                drawPath(path = arrowPath, color = symbolColor)

                // Render terminal pins
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx - 40, cy))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx + 40, cy - 40))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx + 40, cy + 40))

                drawContext.canvas.nativeCanvas.drawText(
                    comp.name,
                    cx - 30,
                    cy - 30,
                    android.graphics.Paint().apply {
                        color = if (isSelected) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                        textSize = 22f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
                drawContext.canvas.nativeCanvas.drawText(
                    comp.valueStr,
                    cx - 30,
                    cy + 52,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.LTGRAY
                        textSize = 19f
                    }
                )
            }
            ComponentType.MOSFET_N -> {
                // Gate plate
                drawLine(color = symbolColor, start = Offset(cx - 15, cy - 20), end = Offset(cx - 15, cy + 20), strokeWidth = 5f)
                // Gate lead
                drawLine(color = symbolColor, start = Offset(cx - 40, cy), end = Offset(cx - 15, cy), strokeWidth = 3.5f)

                // Dynamic body/source channel plate
                drawLine(color = symbolColor, start = Offset(cx - 5, cy - 20), end = Offset(cx - 5, cy + 20), strokeWidth = 3.5f)

                // Drain line
                drawLine(color = symbolColor, start = Offset(cx + 40, cy - 40), end = Offset(cx - 5, cy - 40), strokeWidth = 3.5f)
                drawLine(color = symbolColor, start = Offset(cx - 5, cy - 40), end = Offset(cx - 5, cy - 20), strokeWidth = 3.5f)

                // Source lead
                drawLine(color = symbolColor, start = Offset(cx + 40, cy + 40), end = Offset(cx - 5, cy + 40), strokeWidth = 3.5f)
                drawLine(color = symbolColor, start = Offset(cx - 5, cy + 40), end = Offset(cx - 5, cy + 20), strokeWidth = 3.5f)

                // Internal arrow pointing left from channel body
                val bodyArrow = Path().apply {
                    moveTo(cx - 15, cy)
                    lineTo(cx - 8, cy - 5)
                    lineTo(cx - 8, cy + 5)
                    close()
                }
                drawPath(path = bodyArrow, color = symbolColor)

                // Pins
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx - 40, cy))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx + 40, cy - 40))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx + 40, cy + 40))

                drawContext.canvas.nativeCanvas.drawText(
                    comp.name,
                    cx - 30,
                    cy - 30,
                    android.graphics.Paint().apply {
                        color = if (isSelected) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                        textSize = 22f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
            }
            ComponentType.THYRISTOR -> {
                // Diode Anode triangle and Cathode band (Anode left, Cathode right)
                drawLine(color = symbolColor, start = Offset(cx - 40, cy), end = Offset(cx - 14, cy), strokeWidth = 3.5f)
                
                val path = Path().apply {
                    moveTo(cx - 14, cy - 15)
                    lineTo(cx + 14, cy)
                    lineTo(cx - 14, cy + 15)
                    close()
                }
                drawPath(path = path, color = symbolColor, style = Stroke(width = 3.5f))
                drawLine(color = symbolColor, start = Offset(cx + 14, cy - 15), end = Offset(cx + 14, cy + 15), strokeWidth = 5f)
                drawLine(color = symbolColor, start = Offset(cx + 14, cy), end = Offset(cx + 40, cy), strokeWidth = 3.5f)

                // Gate connection drawing downwards
                drawLine(color = symbolColor, start = Offset(cx - 10, cy + 10), end = Offset(cx - 20, cy + 40), strokeWidth = 3.5f)
                drawLine(color = symbolColor, start = Offset(cx - 20, cy + 40), end = Offset(cx - 40, cy + 40), strokeWidth = 3.5f)

                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx - 40, cy))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx + 40, cy))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx - 40, cy + 40))

                drawContext.canvas.nativeCanvas.drawText(
                    comp.name,
                    cx - 20,
                    cy - 24,
                    android.graphics.Paint().apply {
                        color = if (isSelected) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                        textSize = 22f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
            }
            ComponentType.TRIAC -> {
                // Two overlapping triangles
                val path1 = Path().apply {
                    moveTo(cx - 15, cy - 15)
                    lineTo(cx + 10, cy)
                    lineTo(cx - 15, cy + 15)
                    close()
                }
                val path2 = Path().apply {
                    moveTo(cx + 15, cy - 15)
                    lineTo(cx - 10, cy)
                    lineTo(cx + 15, cy + 15)
                    close()
                }
                drawPath(path = path1, color = symbolColor, style = Stroke(width = 3.5f))
                drawPath(path = path2, color = symbolColor, style = Stroke(width = 3.5f))

                drawLine(color = symbolColor, start = Offset(cx - 40, cy), end = Offset(cx - 10, cy), strokeWidth = 3.5f)
                drawLine(color = symbolColor, start = Offset(cx + 10, cy), end = Offset(cx + 40, cy), strokeWidth = 3.5f)

                // Gate contact line
                drawLine(color = symbolColor, start = Offset(cx - 12, cy + 12), end = Offset(cx - 25, cy + 40), strokeWidth = 3.5f)
                drawLine(color = symbolColor, start = Offset(cx - 25, cy + 40), end = Offset(cx - 40, cy + 40), strokeWidth = 3.5f)

                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx - 40, cy))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx + 40, cy))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx - 40, cy + 40))

                drawContext.canvas.nativeCanvas.drawText(
                    comp.name,
                    cx - 20,
                    cy - 24,
                    android.graphics.Paint().apply {
                        color = if (isSelected) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                        textSize = 22f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
            }
            ComponentType.RELAY -> {
                // Coil box/solenoid indicator on left side
                drawRect(color = symbolColor, topLeft = Offset(cx - 30, cy - 25), size = Size(20f, 50f), style = strokeStyle)
                drawLine(color = symbolColor, start = Offset(cx - 40, cy - 15), end = Offset(cx - 30, cy - 15), strokeWidth = 3.5f)
                drawLine(color = symbolColor, start = Offset(cx - 40, cy + 15), end = Offset(cx - 10, cy + 15), strokeWidth = 3.5f)
                drawLine(color = symbolColor, start = Offset(cx - 10, cy + 15), end = Offset(cx + 40, cy + 15), strokeWidth = 3.5f) // coil 2 terminal

                // Switch arm on right side
                drawLine(color = symbolColor, start = Offset(cx + 40, cy - 15), end = Offset(cx + 10, cy - 15), strokeWidth = 3.5f)
                drawLine(color = symbolColor, start = Offset(cx + 10, cy - 15), end = Offset(cx + 30, cy + 8), strokeWidth = 3.5f) // rotated/open pole
                drawLine(color = symbolColor, start = Offset(cx + 40, cy + 15), end = Offset(cx + 10, cy + 15), strokeWidth = 3.5f)

                // Render 4 terminals
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx - 40, cy - 15))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx + 40, cy + 15)) // Coil contacts
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx + 40, cy - 15)) // Sw A
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx + 40, cy + 15)) // Sw B

                drawContext.canvas.nativeCanvas.drawText(
                    comp.name,
                    cx - 25,
                    cy - 30,
                    android.graphics.Paint().apply {
                        color = if (isSelected) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                        textSize = 22f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
            }
            ComponentType.OPAMP -> {
                // Triangle boundary
                val oPath = Path().apply {
                    moveTo(cx - 20, cy - 30)
                    lineTo(cx + 20, cy)
                    lineTo(cx - 20, cy + 30)
                    close()
                }
                drawPath(path = oPath, color = symbolColor, style = strokeStyle)

                // Plus / Minus input terminals
                drawLine(color = symbolColor, start = Offset(cx - 40, cy - 15), end = Offset(cx - 20, cy - 15), strokeWidth = 3.5f)
                drawLine(color = symbolColor, start = Offset(cx - 40, cy + 15), end = Offset(cx - 20, cy + 15), strokeWidth = 3.5f)

                // Output terminal
                drawLine(color = symbolColor, start = Offset(cx + 20, cy), end = Offset(cx + 40, cy), strokeWidth = 3.5f)

                // Drawings label inside opamp
                drawContext.canvas.nativeCanvas.drawText(
                    "+",
                    cx - 15,
                    cy - 10,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 20f
                    }
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "-",
                    cx - 14,
                    cy + 22,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 22f
                    }
                )

                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx - 40, cy - 15))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx - 40, cy + 15))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx + 40, cy))

                drawContext.canvas.nativeCanvas.drawText(
                    comp.name,
                    cx - 10,
                    cy - 36,
                    android.graphics.Paint().apply {
                        color = if (isSelected) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                        textSize = 22f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
            }
            ComponentType.SUBCIRCUIT -> {
                // Subcircuit component draws as an IC Box
                drawRect(color = symbolColor, topLeft = Offset(cx - 30, cy - 30), size = Size(60f, 60f), style = strokeStyle)
                
                // Draw up to 4 little input/output pin lines dynamically
                drawLine(color = symbolColor, start = Offset(cx - 40, cy - 15), end = Offset(cx - 30, cy - 15), strokeWidth = 3.5f)
                drawLine(color = symbolColor, start = Offset(cx - 40, cy + 15), end = Offset(cx - 30, cy + 15), strokeWidth = 3.5f)
                drawLine(color = symbolColor, start = Offset(cx + 30, cy - 15), end = Offset(cx + 40, cy - 15), strokeWidth = 3.5f)
                drawLine(color = symbolColor, start = Offset(cx + 30, cy + 15), end = Offset(cx + 40, cy + 15), strokeWidth = 3.5f)

                // Terminals
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx - 40, cy - 15))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx - 40, cy + 15))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx + 40, cy - 15))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f, center = Offset(cx + 40, cy + 15))

                // Render name
                drawContext.canvas.nativeCanvas.drawText(
                    comp.valueStr,
                    cx - 25,
                    cy - 35,
                    android.graphics.Paint().apply {
                        color = if (isSelected) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                        textSize = 18f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
                drawContext.canvas.nativeCanvas.drawText(
                    comp.name,
                    cx - 20,
                    cy + 8,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.CYAN
                        textSize = 20f
                    }
                )
            }
            ComponentType.PORT -> {
                drawRect(color = symbolColor, topLeft = Offset(cx - 8, cy - 8), size = Size(16f, 16f), style = strokeStyle)
                drawCircle(color = Color(0xFFFFB4AB), radius = 3.5f, center = Offset(cx, cy))
                drawContext.canvas.nativeCanvas.drawText(
                    comp.valueStr,
                    cx - 20,
                    cy - 12,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.CYAN
                        textSize = 17f
                    }
                )
            }
        }
    }
}
