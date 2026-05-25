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
import androidx.compose.foundation.BorderStroke
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
    modifier: Modifier = Modifier,
    onUpdateComponent: (Component, Component) -> Unit = { _, _ -> },
    onDoubleTapComponent: (Component) -> Unit = {},
    onCanvasClick: () -> Unit = {},
    placingValue: String? = null,
    multiSelectedComponents: MutableList<Component> = remember { mutableStateListOf() },
    isMultiSelectMode: Boolean = false,
    onMultiSelectModeChange: (Boolean) -> Unit = {},
    showMultiSelectActions: Boolean = false,
    onShowMultiSelectActionsChange: (Boolean) -> Unit = {},
    clipboardComponents: MutableList<Component> = remember { mutableStateListOf() },
    clipboardWires: MutableList<Wire> = remember { mutableStateListOf() },
    onPushHistoryState: () -> Unit = {}
) {
    // Grid settings
    val gridSize = 40f // dp-adjacent sizes
    
    // Viewport panning offset
    var scrollOffsetX by remember { mutableStateOf(0f) }
    var scrollOffsetY by remember { mutableStateOf(0f) }

    // Zoom State
    var zoomScale by remember { mutableStateOf(1.0f) }

    // Wire placement state
    var wireStartPoint by remember { mutableStateOf<GridPoint?>(null) }
    var hoverGridPoint by remember { mutableStateOf<GridPoint?>(null) }

    // Drag-to-Connect state
    var dragConnectStart by remember { mutableStateOf<GridPoint?>(null) }
    var dragConnectCurrentPos by remember { mutableStateOf<Offset?>(null) }

    // Double-tap tracking
    var lastTapTime by remember { mutableStateOf(0L) }
    var lastTapPosition by remember { mutableStateOf(Offset.Zero) }

    var isSingleComponentDragActive by remember { mutableStateOf(false) }

    LaunchedEffect(selectedComponent) {
        isSingleComponentDragActive = false
    }

    // Updated states to avoid recreation of high-frequency pointerInput blocks
    val currentActiveTool by rememberUpdatedState(activeTool)
    val currentPlacingType by rememberUpdatedState(placingType)
    val currentPlacingValue by rememberUpdatedState(placingValue)
    val currentComponents by rememberUpdatedState(components)
    val currentWires by rememberUpdatedState(wires)
    val currentSimResult by rememberUpdatedState(simResult)
    val currentScrollOffsetX by rememberUpdatedState(scrollOffsetX)
    val currentScrollOffsetY by rememberUpdatedState(scrollOffsetY)
    val currentIsMultiSelectMode by rememberUpdatedState(isMultiSelectMode)
    val currentOnMultiSelectModeChange by rememberUpdatedState(onMultiSelectModeChange)
    val currentOnShowMultiSelectActionsChange by rememberUpdatedState(onShowMultiSelectActionsChange)
    val currentOnPushHistoryState by rememberUpdatedState(onPushHistoryState)

    val currentOnSelectComponent by rememberUpdatedState(onSelectComponent)
    val currentOnAddComponent by rememberUpdatedState(onAddComponent)
    val currentOnAddWire by rememberUpdatedState(onAddWire)
    val currentOnDeleteComponent by rememberUpdatedState(onDeleteComponent)
    val currentOnDeleteWire by rememberUpdatedState(onDeleteWire)
    val currentOnProbeNode by rememberUpdatedState(onProbeNode)
    val currentOnCanvasClick by rememberUpdatedState(onCanvasClick)

    // Convert pixel inputs to Grid Coordinates safely
    fun pxToGrid(pxX: Float, pxY: Float): GridPoint {
        val div = gridSize * zoomScale
        val gx = if (div > 0.1f && div.isFinite()) {
            val raw = ((pxX - scrollOffsetX) / div).roundToInt()
            raw.coerceIn(-300, 300)
        } else {
            0
        }
        val gy = if (div > 0.1f && div.isFinite()) {
            val raw = ((pxY - scrollOffsetY) / div).roundToInt()
            raw.coerceIn(-300, 300)
        } else {
            0
        }
        return GridPoint(gx, gy)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1A1C1E)) // Elegant Dark Background
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()

        val scope = rememberCoroutineScope()
        val context = LocalContext.current

        val selectedWires = if (isMultiSelectMode) {
            val selectedPins = multiSelectedComponents.flatMap { it.getPins() }.toSet()
            wires.filter { wire ->
                selectedPins.any { it.x == wire.start.x && it.y == wire.start.y } &&
                selectedPins.any { it.x == wire.end.x && it.y == wire.end.y }
            }
        } else {
            emptyList()
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val downChange = awaitFirstDown(requireUnconsumed = true)
                        var lastPosition = downChange.position
                        val startTime = System.currentTimeMillis()
                        var isPinching = false

                        // Find if they clicked near/on a component
                        val gpAtDown = pxToGrid(downChange.position.x, downChange.position.y)
                        var initialTouchedComp = currentComponents.find { comp ->
                            val dist = abs(comp.gridX - gpAtDown.x) + abs(comp.gridY - gpAtDown.y)
                            dist <= 1
                        }

                        // Find if they clicked a terminal pin of a component (for wiring start)
                        val initialTouchedPin = currentComponents.flatMap { it.getPins() }.find { pin ->
                            val pinPxX = pin.x * gridSize * zoomScale + scrollOffsetX
                            val pinPxY = pin.y * gridSize * zoomScale + scrollOffsetY
                            (Offset(pinPxX, pinPxY) - downChange.position).getDistance() < 35f
                        }

                        var isLongPressTriggered = false
                        val longPressJob = scope.launch {
                            kotlinx.coroutines.delay(500)
                            if (!isPinching && (lastPosition - downChange.position).getDistance() < 15f) {
                                isLongPressTriggered = true
                                if (initialTouchedComp != null) {
                                    if (isSingleComponentDragActive && selectedComponent?.id == initialTouchedComp!!.id) {
                                        // Single-component drag mode is active, do not enter multi-select mode
                                    } else if (!currentIsMultiSelectMode) {
                                        currentOnMultiSelectModeChange(true)
                                        multiSelectedComponents.clear()
                                        multiSelectedComponents.add(initialTouchedComp!!)
                                        currentOnShowMultiSelectActionsChange(true)
                                    } else {
                                        if (multiSelectedComponents.any { it.id == initialTouchedComp!!.id }) {
                                            currentOnShowMultiSelectActionsChange(true)
                                        }
                                    }
                                }
                            }
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val pointersCount = event.changes.size
                            if (pointersCount > 1 || (lastPosition - downChange.position).getDistance() > 15f) {
                                isPinching = true
                                longPressJob.cancel()
                            }

                            val anyPressed = event.changes.any { it.pressed }
                            if (!anyPressed) {
                                // All pointers released
                                longPressJob.cancel()
                                if (!isLongPressTriggered) {
                                    val endTime = System.currentTimeMillis()
                                    val totalDragDistance = (lastPosition - downChange.position).getDistance()
                                    val isTap = (endTime - startTime < 300L) && (totalDragDistance < 15f) && !isPinching

                                    if (isTap) {
                                        val gp = pxToGrid(downChange.position.x, downChange.position.y)
                                        val curTime = System.currentTimeMillis()
                                        val isDoubleTap = !currentIsMultiSelectMode && (curTime - lastTapTime < 300L) && 
                                            (downChange.position - lastTapPosition).getDistance() < 30f

                                        if (isDoubleTap) {
                                            val tappedComp = currentComponents.find { comp ->
                                                val dist = abs(comp.gridX - gp.x) + abs(comp.gridY - gp.y)
                                                dist <= 1
                                            }
                                            if (tappedComp != null) {
                                                onDoubleTapComponent(tappedComp)
                                            }
                                        } else {
                                            lastTapTime = curTime
                                            lastTapPosition = downChange.position
 
                                            val tappedCompForMultiSelect = if (currentIsMultiSelectMode) {
                                                currentComponents.find { comp ->
                                                    val dist = abs(comp.gridX - gp.x) + abs(comp.gridY - gp.y)
                                                    dist <= 1
                                                }
                                            } else null

                                            if (currentIsMultiSelectMode && tappedCompForMultiSelect != null) {
                                                val alreadySelIdx = multiSelectedComponents.indexOfFirst { it.id == tappedCompForMultiSelect.id }
                                                if (alreadySelIdx != -1) {
                                                    multiSelectedComponents.removeAt(alreadySelIdx)
                                                    if (multiSelectedComponents.isEmpty()) {
                                                        currentOnMultiSelectModeChange(false)
                                                        currentOnShowMultiSelectActionsChange(false)
                                                    }
                                                } else {
                                                    multiSelectedComponents.add(tappedCompForMultiSelect)
                                                }
                                            } else {

                                            if (currentActiveTool == WorkspaceTool.DRAW_WIRE) {
                                                val targetPin = initialTouchedPin ?: gp
                                                val currentStart = wireStartPoint
                                                if (currentStart == null) {
                                                    wireStartPoint = targetPin
                                                } else {
                                                    if (currentStart != targetPin) {
                                                        val midPoint = GridPoint(targetPin.x, currentStart.y)
                                                        val wire1 = Wire("${System.currentTimeMillis()}_1", currentStart, midPoint)
                                                        val wire2 = Wire("${System.currentTimeMillis()}_2", midPoint, targetPin)
                                                        currentOnAddWire(wire1)
                                                        currentOnAddWire(wire2)
                                                    }
                                                    wireStartPoint = null
                                                }
                                            } else if (currentActiveTool == WorkspaceTool.SELECT) {
                                                val tappedComp = currentComponents.find { comp ->
                                                    val dist = abs(comp.gridX - gp.x) + abs(comp.gridY - gp.y)
                                                    dist <= 1
                                                }
                                                if (currentIsMultiSelectMode) {
                                                    if (tappedComp != null) {
                                                        val alreadySelIdx = multiSelectedComponents.indexOfFirst { it.id == tappedComp.id }
                                                        if (alreadySelIdx != -1) {
                                                            multiSelectedComponents.removeAt(alreadySelIdx)
                                                            if (multiSelectedComponents.isEmpty()) {
                                                                currentOnMultiSelectModeChange(false)
                                                                currentOnShowMultiSelectActionsChange(false)
                                                            }
                                                        } else {
                                                            multiSelectedComponents.add(tappedComp)
                                                        }
                                                    }
                                                } else {
                                                    currentOnSelectComponent(tappedComp)
                                                    if (tappedComp == null) {
                                                        currentOnCanvasClick()
                                                    }
                                                }
                                            } else if (currentActiveTool == WorkspaceTool.PLACE_COMPONENT) {
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
                                                    ComponentType.RESISTOR -> currentPlacingValue ?: "1k"
                                                    ComponentType.CAPACITOR -> currentPlacingValue ?: "10u"
                                                    ComponentType.INDUCTOR -> currentPlacingValue ?: "1m"
                                                    ComponentType.DIODE -> currentPlacingValue ?: "D1N4148"
                                                    ComponentType.VOLTAGE_SOURCE -> currentPlacingValue ?: "SINE(0 10 1k)"
                                                    ComponentType.CURRENT_SOURCE -> currentPlacingValue ?: "1m"
                                                    ComponentType.GROUND -> "0"
                                                    ComponentType.TRANSISTOR_NPN -> currentPlacingValue ?: "BC547"
                                                    ComponentType.MOSFET_N -> currentPlacingValue ?: "IRF540"
                                                    ComponentType.THYRISTOR -> currentPlacingValue ?: "MCR100"
                                                    ComponentType.RELAY -> currentPlacingValue ?: "5V Relay"
                                                    ComponentType.TRIAC -> currentPlacingValue ?: "BT136"
                                                    ComponentType.OPAMP -> currentPlacingValue ?: "UA741"
                                                    ComponentType.SUBCIRCUIT -> currentPlacingValue ?: "MySubcircuit"
                                                    ComponentType.PORT -> currentPlacingValue ?: "PortA"
                                                }
                                                val id = "${prefix}_${System.currentTimeMillis()}"
                                                val comp = Component(
                                                    id = id,
                                                    type = currentPlacingType,
                                                    name = "$prefix$sameTypeCount",
                                                    valueStr = defaultValue,
                                                    gridX = gp.x,
                                                    gridY = gp.y
                                                )
                                                currentOnAddComponent(comp)
                                            } else if (currentActiveTool == WorkspaceTool.ERASE) {
                                                val comp = currentComponents.find { c ->
                                                    abs(c.gridX - gp.x) + abs(c.gridY - gp.y) <= 1
                                                }
                                                if (comp != null) {
                                                    currentOnDeleteComponent(comp)
                                                } else {
                                                    val wire = currentWires.find { w -> w.contains(gp) }
                                                    if (wire != null) {
                                                        currentOnDeleteWire(wire)
                                                    }
                                                }
                                            } else if (currentActiveTool == WorkspaceTool.PROBE) {
                                                if (currentSimResult != null) {
                                                    val probedWire = currentWires.find { it.contains(gp) }
                                                    val probedComp = currentComponents.find { c -> c.getPins().contains(gp) }
                                                    if (probedWire != null || probedComp != null) {
                                                        currentOnProbeNode(gp.toString())
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                }

                                val finishedStart = dragConnectStart
                                val finishedPos = dragConnectCurrentPos
                                if (finishedStart != null && finishedPos != null) {
                                    val destGP = pxToGrid(finishedPos.x, finishedPos.y)
                                    val destTouchedPin = currentComponents.flatMap { it.getPins() }.find { pin ->
                                        val pinPxX = pin.x * gridSize * zoomScale + scrollOffsetX
                                        val pinPxY = pin.y * gridSize * zoomScale + scrollOffsetY
                                        (Offset(pinPxX, pinPxY) - finishedPos).getDistance() < 35f
                                    }
                                    val finalTarget = destTouchedPin ?: destGP
                                    if (finishedStart != finalTarget) {
                                        val midPoint = GridPoint(finalTarget.x, finishedStart.y)
                                        val wire1 = Wire("${System.currentTimeMillis()}_1", finishedStart, midPoint)
                                        val wire2 = Wire("${System.currentTimeMillis()}_2", midPoint, finalTarget)
                                        currentOnAddWire(wire1)
                                        currentOnAddWire(wire2)
                                    }
                                }
                                dragConnectStart = null
                                dragConnectCurrentPos = null
                                isSingleComponentDragActive = false
                                break
                            }

                            if (isPinching && event.changes.size >= 2) {
                                val p1 = event.changes[0].position
                                val p2 = event.changes[1].position
                                val prevP1 = event.changes[0].previousPosition
                                val prevP2 = event.changes[1].previousPosition

                                val currentDist = (p1 - p2).getDistance()
                                val prevDist = (prevP1 - prevP2).getDistance()

                                if (prevDist > 0) {
                                    val scaleFactor = currentDist / prevDist
                                    zoomScale = (zoomScale * scaleFactor).coerceIn(0.5f, 2.5f)
                                }

                                val currentMid = (p1 + p2) / 2f
                                val prevMid = (prevP1 + prevP2) / 2f
                                scrollOffsetX += (currentMid.x - prevMid.x)
                                scrollOffsetY += (currentMid.y - prevMid.y)

                                event.changes.forEach { it.consume() }
                            } else if (!isPinching) {
                                val change = event.changes.first()
                                if (change.pressed) {
                                    val dragAmount = change.position - change.previousPosition
                                    if (dragAmount.getDistance() > 1f) {
                                        lastPosition = change.position
                                        if (currentActiveTool == WorkspaceTool.DRAW_WIRE || initialTouchedPin != null) {
                                            if (dragConnectStart == null) {
                                                dragConnectStart = initialTouchedPin ?: gpAtDown
                                            }
                                            dragConnectCurrentPos = change.position
                                        } else if (initialTouchedComp != null) {
                                            val currentGP = pxToGrid(change.position.x, change.position.y)
                                            if (currentIsMultiSelectMode) {
                                                if (isLongPressTriggered && multiSelectedComponents.any { it.id == initialTouchedComp!!.id }) {
                                                    val dx = currentGP.x - initialTouchedComp!!.gridX
                                                    val dy = currentGP.y - initialTouchedComp!!.gridY
                                                    if (dx != 0 || dy != 0) {
                                                        currentOnPushHistoryState()
                                                        val movedComponents = multiSelectedComponents.map { oldComp ->
                                                            val updatedComp = oldComp.copy(gridX = oldComp.gridX + dx, gridY = oldComp.gridY + dy)
                                                            onUpdateComponent(oldComp, updatedComp)
                                                            updatedComp
                                                        }
                                                        multiSelectedComponents.clear()
                                                        multiSelectedComponents.addAll(movedComponents)
                                                        
                                                        val selectedPins = movedComponents.flatMap { it.getPins() }.toSet()
                                                        val selWires = currentWires.filter { wire ->
                                                            selectedPins.any { it.x == wire.start.x && it.y == wire.start.y } &&
                                                            selectedPins.any { it.x == wire.end.x && it.y == wire.end.y }
                                                        }
                                                        selWires.forEach { w ->
                                                            val updatedWire = w.copy(
                                                                id = "W_drag_${System.currentTimeMillis()}_${(Math.random()*1000).toInt()}",
                                                                start = GridPoint(w.start.x + dx, w.start.y + dy),
                                                                end = GridPoint(w.end.x + dx, w.end.y + dy)
                                                            )
                                                            currentOnDeleteWire(w)
                                                            currentOnAddWire(updatedWire)
                                                        }
                                                        initialTouchedComp = initialTouchedComp!!.copy(gridX = initialTouchedComp!!.gridX + dx, gridY = initialTouchedComp!!.gridY + dy)
                                                    }
                                                } else {
                                                    scrollOffsetX += dragAmount.x
                                                    scrollOffsetY += dragAmount.y
                                                }
                                            } else {
                                                if (isSingleComponentDragActive && isLongPressTriggered && selectedComponent?.id == initialTouchedComp!!.id) {
                                                    if (initialTouchedComp!!.gridX != currentGP.x || initialTouchedComp!!.gridY != currentGP.y) {
                                                        val updatedComp = initialTouchedComp!!.copy(gridX = currentGP.x, gridY = currentGP.y)
                                                        onUpdateComponent(initialTouchedComp!!, updatedComp)
                                                        initialTouchedComp = updatedComp
                                                    }
                                                } else {
                                                    scrollOffsetX += dragAmount.x
                                                    scrollOffsetY += dragAmount.y
                                                }
                                            }
                                        } else {
                                            scrollOffsetX += dragAmount.x
                                            scrollOffsetY += dragAmount.y
                                        }
                                        change.consume()
                                    }
                                }
                            }
                        }
                    }
                }
        ) {
            // Ensure inputs are completely finite and valid to prevent Skia native crashes or infinite loops
            var zS = zoomScale
            if (!zS.isFinite() || zS.isNaN() || zS < 0.1f) {
                zS = 1.0f
                zoomScale = 1.0f
            }
            if (!scrollOffsetX.isFinite() || scrollOffsetX.isNaN()) {
                scrollOffsetX = 0f
            }
            if (!scrollOffsetY.isFinite() || scrollOffsetY.isNaN()) {
                scrollOffsetY = 0f
            }

            // Draw grid dots safely (clamping canvas boundaries to prevent infinite loops in unconstrained view constraints)
            val safeWidthPx = if (widthPx.isFinite()) widthPx.coerceIn(0f, 3000f) else 1500f
            val safeHeightPx = if (heightPx.isFinite()) heightPx.coerceIn(0f, 3000f) else 1500f

            val divX = gridSize * zS
            val divY = gridSize * zS
            
            val startXRaw = if (divX > 0.1f) (-scrollOffsetX / divX).toInt() - 2 else -2
            val countX = if (divX > 0.1f) (safeWidthPx / divX).toInt() + 4 else 10
            val startYRaw = if (divY > 0.1f) (-scrollOffsetY / divY).toInt() - 2 else -2
            val countY = if (divY > 0.1f) (safeHeightPx / divY).toInt() + 4 else 10

            val startGridX = startXRaw.coerceIn(-300, 300)
            val endGridX = (startXRaw + countX).coerceIn(-300, 300)
            val startGridY = startYRaw.coerceIn(-300, 300)
            val endGridY = (startYRaw + countY).coerceIn(-300, 300)

            for (gx in startGridX..endGridX) {
                for (gy in startGridY..endGridY) {
                    val cx = gx * (gridSize * zS) + scrollOffsetX
                    val cy = gy * (gridSize * zS) + scrollOffsetY
                    drawCircle(
                        color = Color(0xFF44474E), // Elegant Dark grid dots
                        radius = 2.0f * zoomScale,
                        center = Offset(cx, cy)
                    )
                }
            }

            // Draw real-time yellow draft wire for Drag-to-Connect
            val dStart = dragConnectStart
            val dPos = dragConnectCurrentPos
            if (dStart != null && dPos != null) {
                val startPxX = dStart.x * (gridSize * zoomScale) + scrollOffsetX
                val startPxY = dStart.y * (gridSize * zoomScale) + scrollOffsetY
                
                val midPxX = dPos.x
                val midPxY = startPxY
                
                drawLine(
                    color = Color(0xFFFACC15).copy(alpha = 0.8f),
                    start = Offset(startPxX, startPxY),
                    end = Offset(midPxX, midPxY),
                    strokeWidth = 4f * zoomScale,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = Color(0xFFFACC15).copy(alpha = 0.8f),
                    start = Offset(midPxX, midPxY),
                    end = Offset(dPos.x, dPos.y),
                    strokeWidth = 4f * zoomScale,
                    cap = StrokeCap.Round
                )
            }

            // Draw Wire routing preview if drafting (for tap-to-tap)
            val wStart = wireStartPoint
            if (activeTool == WorkspaceTool.DRAW_WIRE && wStart != null && dragConnectStart == null) {
                val startPxX = wStart.x * (gridSize * zoomScale) + scrollOffsetX
                val startPxY = wStart.y * (gridSize * zoomScale) + scrollOffsetY
                drawCircle(
                    color = Color(0xFFFACC15).copy(alpha = 0.5f),
                    radius = 8f * zoomScale,
                    center = Offset(startPxX, startPxY)
                )
            }

            val selectedWires = if (isMultiSelectMode) {
                val selectedPins = multiSelectedComponents.flatMap { it.getPins() }.toSet()
                wires.filter { wire ->
                    selectedPins.any { it.x == wire.start.x && it.y == wire.start.y } &&
                    selectedPins.any { it.x == wire.end.x && it.y == wire.end.y }
                }
            } else {
                emptyList()
            }

            // Draw Wires
            wires.forEach { wire ->
                val sx = wire.start.x * (gridSize * zoomScale) + scrollOffsetX
                val sy = wire.start.y * (gridSize * zoomScale) + scrollOffsetY
                val ex = wire.end.x * (gridSize * zoomScale) + scrollOffsetX
                val ey = wire.end.y * (gridSize * zoomScale) + scrollOffsetY
                
                val isWireSelected = isMultiSelectMode && selectedWires.any { it.id == wire.id }
                drawLine(
                    color = if (isWireSelected) Color(0xFF00FFCC) else Color(0xFFD1E4FF),
                    start = Offset(sx, sy),
                    end = Offset(ex, ey),
                    strokeWidth = (if (isWireSelected) 5f else 3f) * zoomScale,
                    cap = StrokeCap.Round
                )
            }

            // Draw Components
            components.forEach { comp ->
                val cx = comp.gridX * (gridSize * zoomScale) + scrollOffsetX
                val cy = comp.gridY * (gridSize * zoomScale) + scrollOffsetY
                val isCompSelected = if (isMultiSelectMode) {
                    multiSelectedComponents.any { it.id == comp.id }
                } else {
                    selectedComponent?.id == comp.id
                }

                drawComponentSymbol(
                    comp = comp,
                    cx = cx,
                    cy = cy,
                    isSelected = isCompSelected,
                    gridSize = gridSize * zoomScale
                )

                if (isMultiSelectMode && isCompSelected) {
                    drawCircle(
                        color = Color(0xFF00FFCC),
                        radius = (gridSize * zoomScale) * 1.5f,
                        center = Offset(cx, cy),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f * zoomScale)
                    )
                }
            }

            // Draw wire nodes / junctions (connection blobs)
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
                    val cx = pt.x * (gridSize * zoomScale) + scrollOffsetX
                    val cy = pt.y * (gridSize * zoomScale) + scrollOffsetY
                    drawCircle(
                        color = Color(0xFFD1E4FF),
                        radius = 5f * zoomScale,
                        center = Offset(cx, cy)
                    )
                }
            }

            // Draw diagnostic probe tips if simulation has run
            if (activeTool == WorkspaceTool.PROBE && simResult != null) {
                wires.forEach { w ->
                    val cxS = w.start.x * (gridSize * zoomScale) + scrollOffsetX
                    val cyS = w.start.y * (gridSize * zoomScale) + scrollOffsetY
                    drawCircle(
                        color = Color(0xFFFACC15).copy(alpha = 0.3f),
                        radius = 8f * zoomScale,
                        center = Offset(cxS, cyS)
                    )
                    val cxE = w.end.x * (gridSize * zoomScale) + scrollOffsetX
                    val cyE = w.end.y * (gridSize * zoomScale) + scrollOffsetY
                    drawCircle(
                        color = Color(0xFFFACC15).copy(alpha = 0.3f),
                        radius = 8f * zoomScale,
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

        // --- FLOATING CONTEXT MENU ---
        if (selectedComponent != null && !isMultiSelectMode) {
            val cx = selectedComponent.gridX * (gridSize * zoomScale) + scrollOffsetX
            val cy = selectedComponent.gridY * (gridSize * zoomScale) + scrollOffsetY
            
            val density = androidx.compose.ui.platform.LocalDensity.current
            val cxDp = with(density) { cx.toDp() }
            val cyDp = with(density) { cy.toDp() }
            
            Card(
                modifier = Modifier
                    .offset(x = cxDp - 120.dp, y = cyDp - 80.dp)
                    .width(240.dp)
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                elevation = CardDefaults.cardElevation(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF202124))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            val nextRot = when (selectedComponent.orientation) {
                                Orientation.DEG_0 -> Orientation.DEG_90
                                Orientation.DEG_90 -> Orientation.DEG_180
                                Orientation.DEG_180 -> Orientation.DEG_270
                                Orientation.DEG_270 -> Orientation.DEG_0
                            }
                            onUpdateComponent(selectedComponent, selectedComponent.copy(orientation = nextRot))
                        }
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Refresh, contentDescription = "Rotate", tint = Color.White, modifier = Modifier.size(18.dp))
                            Text("Rotate", fontSize = 9.sp, color = Color.LightGray)
                        }
                    }
                    
                    IconButton(
                        onClick = {
                            onDoubleTapComponent(selectedComponent)
                        }
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Edit, contentDescription = "Value", tint = Color.White, modifier = Modifier.size(18.dp))
                            Text("Value", fontSize = 9.sp, color = Color.LightGray)
                        }
                    }

                    IconButton(
                        onClick = {
                            isSingleComponentDragActive = !isSingleComponentDragActive
                            if (isSingleComponentDragActive) {
                                Toast.makeText(context, "Drag mode active. Long touch & move component to reposition it.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Build, 
                                contentDescription = "Drag", 
                                tint = if (isSingleComponentDragActive) Color(0xFF38BDF8) else Color.White, 
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Drag", 
                                fontSize = 9.sp, 
                                color = if (isSingleComponentDragActive) Color(0xFF38BDF8) else Color.LightGray
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            onDeleteComponent(selectedComponent)
                            onSelectComponent(null)
                        }
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red, modifier = Modifier.size(18.dp))
                            Text("Delete", fontSize = 9.sp, color = Color.Red)
                        }
                    }
                }
            }
        }

        // --- PIN TARGET MAGNIFYING GLASS ---
        val dPos = dragConnectCurrentPos
        if (dPos != null && dragConnectStart != null) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val dPosDpX = with(density) { dPos.x.toDp() }
            val dPosDpY = with(density) { dPos.y.toDp() }
            
            Box(
                modifier = Modifier
                    .offset(x = dPosDpX - 60.dp, y = dPosDpY - 140.dp)
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2D3035))
                    .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                val currentGridPt = pxToGrid(dPos.x, dPos.y)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "PIN TARGET",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )
                    Text(
                        text = "X: ${currentGridPt.x}, Y: ${currentGridPt.y}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    
                    val hoveredComp = currentComponents.find { c ->
                        c.getPins().contains(currentGridPt)
                    }
                    if (hoveredComp != null) {
                        Text(
                            text = hoveredComp.name,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF4ADE80)
                        )
                    } else {
                        Text(
                            text = "Empty Grid",
                            fontSize = 10.sp,
                            color = Color.LightGray
                        )
                    }
                }
            }
        }

        // --- FLOATING MULTI-SELECT OVERLAY ACTIONS BANNER PASSED TO PARENT VIEW ---


        // --- FLOATING PASTE BUTTON ---
        if (clipboardComponents.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .padding(top = 100.dp)
            ) {
                ExtendedFloatingActionButton(
                    onClick = {
                        onPushHistoryState()
                        
                        val idMap = mutableMapOf<String, String>()
                        val newComps = clipboardComponents.map { oldComp ->
                            val newId = "${oldComp.type.name.take(3)}_${System.currentTimeMillis()}_${(Math.random()*1000).toInt()}"
                            idMap[oldComp.id] = newId
                            oldComp.copy(
                                id = newId,
                                gridX = oldComp.gridX + 3,
                                gridY = oldComp.gridY + 3,
                                name = oldComp.name + "_copy"
                            )
                        }
                        
                        val newWires = clipboardWires.map { oldWire ->
                            Wire(
                                id = "W_paste_${System.currentTimeMillis()}_${(Math.random()*1000).toInt()}",
                                start = GridPoint(oldWire.start.x + 3, oldWire.start.y + 3),
                                end = GridPoint(oldWire.end.x + 3, oldWire.end.y + 3)
                            )
                        }
                        
                        newComps.forEach { currentOnAddComponent(it) }
                        newWires.forEach { currentOnAddWire(it) }
                        
                        currentOnMultiSelectModeChange(true)
                        multiSelectedComponents.clear()
                        multiSelectedComponents.addAll(newComps)
                        currentOnShowMultiSelectActionsChange(true)
                        
                        Toast.makeText(context, "Pasted ${newComps.size} elements", Toast.LENGTH_SHORT).show()
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = "Paste Clipboard Items") },
                    text = { Text("Paste Clip (${clipboardComponents.size})") },
                    containerColor = Color(0xFF00315C),
                    contentColor = Color.White,
                    modifier = Modifier.testTag("floating_paste_button")
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
    val scale = gridSize / 40f
    val gS = gridSize
    val symbolColor = if (isSelected) Color(0xFFFFB4AB) else Color(0xFFD1E4FF) // Elegant Dark component colors
    val strokeStyle = Stroke(width = 3.5f * scale, cap = StrokeCap.Round)

    withTransform({
        // Automatically rotate the drawing canvas relative to grid point center
        rotate(comp.orientation.degrees, Offset(cx, cy))
    }) {
        when (comp.type) {
            ComponentType.RESISTOR -> {
                // Drawing horizontal resistor relative to (cx, cy)
                val path = Path().apply {
                    moveTo(cx - gS, cy)
                    lineTo(cx - 0.6f * gS, cy)
                    
                    // Zigzags
                    lineTo(cx - 0.45f * gS, cy - 0.3f * gS)
                    lineTo(cx - 0.3f * gS, cy + 0.3f * gS)
                    lineTo(cx - 0.15f * gS, cy - 0.3f * gS)
                    lineTo(cx, cy + 0.3f * gS)
                    lineTo(cx + 0.15f * gS, cy - 0.3f * gS)
                    lineTo(cx + 0.3f * gS, cy + 0.3f * gS)
                    lineTo(cx + 0.45f * gS, cy - 0.3f * gS)
                    
                    lineTo(cx + 0.6f * gS, cy)
                    lineTo(cx + gS, cy)
                }
                drawPath(path = path, color = symbolColor, style = strokeStyle)
                
                // Red Terminal Pins represent connectors
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx - gS, cy))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx + gS, cy))

                // Label Text
                drawContext.canvas.nativeCanvas.drawText(
                    comp.name,
                    cx - 0.5f * gS,
                    cy - 0.55f * gS,
                    android.graphics.Paint().apply {
                        color = if (isSelected) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                        textSize = 22f * scale
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
                drawContext.canvas.nativeCanvas.drawText(
                    comp.valueStr,
                    cx - 0.5f * gS,
                    cy + 0.85f * gS,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.LTGRAY
                        textSize = 19f * scale
                    }
                )
            }
            ComponentType.CAPACITOR -> {
                // Capacitor parallel plates
                drawLine(color = symbolColor, start = Offset(cx - gS, cy), end = Offset(cx - 0.175f * gS, cy), strokeWidth = 3.5f * scale)
                drawLine(color = symbolColor, start = Offset(cx - 0.175f * gS, cy - 0.5f * gS), end = Offset(cx - 0.175f * gS, cy + 0.5f * gS), strokeWidth = 5f * scale)
                drawLine(color = symbolColor, start = Offset(cx + 0.175f * gS, cy - 0.5f * gS), end = Offset(cx + 0.175f * gS, cy + 0.5f * gS), strokeWidth = 5f * scale)
                drawLine(color = symbolColor, start = Offset(cx + 0.175f * gS, cy), end = Offset(cx + gS, cy), strokeWidth = 3.5f * scale)

                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx - gS, cy))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx + gS, cy))

                drawContext.canvas.nativeCanvas.drawText(
                    comp.name,
                    cx - 0.5f * gS,
                    cy - 0.65f * gS,
                    android.graphics.Paint().apply {
                        color = if (isSelected) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                        textSize = 22f * scale
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
                drawContext.canvas.nativeCanvas.drawText(
                    comp.valueStr,
                    cx - 0.5f * gS,
                    cy + 0.95f * gS,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.LTGRAY
                        textSize = 19f * scale
                    }
                )
            }
            ComponentType.INDUCTOR -> {
                // Series of solenoid curves (drawn as loops)
                val path = Path().apply {
                    moveTo(cx - gS, cy)
                    lineTo(cx - 0.6f * gS, cy)
                    
                    // Simple schematic coil arcs using quadratic Beziers
                    quadraticTo(cx - 0.45f * gS, cy - 0.35f * gS, cx - 0.3f * gS, cy)
                    quadraticTo(cx - 0.15f * gS, cy - 0.35f * gS, cx, cy)
                    quadraticTo(cx + 0.15f * gS, cy - 0.35f * gS, cx + 0.3f * gS, cy)
                    quadraticTo(cx + 0.45f * gS, cy - 0.35f * gS, cx + 0.6f * gS, cy)
                    
                    lineTo(cx + gS, cy)
                }
                drawPath(path = path, color = symbolColor, style = strokeStyle)

                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx - gS, cy))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx + gS, cy))

                drawContext.canvas.nativeCanvas.drawText(
                    comp.name,
                    cx - 0.5f * gS,
                    cy - 0.55f * gS,
                    android.graphics.Paint().apply {
                        color = if (isSelected) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                        textSize = 22f * scale
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
                drawContext.canvas.nativeCanvas.drawText(
                    comp.valueStr,
                    cx - 0.5f * gS,
                    cy + 0.75f * gS,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.LTGRAY
                        textSize = 19f * scale
                    }
                )
            }
            ComponentType.DIODE -> {
                // Diode Anode triangle and Cathode band (Anode left, Cathode right)
                drawLine(color = symbolColor, start = Offset(cx - gS, cy), end = Offset(cx - 0.35f * gS, cy), strokeWidth = 3.5f * scale)
                
                // Triangle pointing right
                val path = Path().apply {
                    moveTo(cx - 0.35f * gS, cy - 0.375f * gS)
                    lineTo(cx + 0.35f * gS, cy)
                    lineTo(cx - 0.35f * gS, cy + 0.375f * gS)
                    close()
                }
                drawPath(path = path, color = symbolColor, style = Stroke(width = 3.5f * scale))
                
                // Cathode bar
                drawLine(color = symbolColor, start = Offset(cx + 0.35f * gS, cy - 0.375f * gS), end = Offset(cx + 0.35f * gS, cy + 0.375f * gS), strokeWidth = 5f * scale)
                
                drawLine(color = symbolColor, start = Offset(cx + 0.35f * gS, cy), end = Offset(cx + gS, cy), strokeWidth = 3.5f * scale)

                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx - gS, cy))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx + gS, cy))

                drawContext.canvas.nativeCanvas.drawText(
                    comp.name,
                    cx - 0.5f * gS,
                    cy - 0.6f * gS,
                    android.graphics.Paint().apply {
                        color = if (isSelected) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                        textSize = 22f * scale
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
                drawContext.canvas.nativeCanvas.drawText(
                    comp.valueStr,
                    cx - 0.5f * gS,
                    cy + 0.85f * gS,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.LTGRAY
                        textSize = 19f * scale
                    }
                )
            }
            ComponentType.VOLTAGE_SOURCE -> {
                // Circle voltage source
                drawLine(color = symbolColor, start = Offset(cx - gS, cy), end = Offset(cx - 0.5f * gS, cy), strokeWidth = 3.5f * scale)
                drawCircle(color = symbolColor, radius = 0.5f * gS, center = Offset(cx, cy), style = strokeStyle)
                drawLine(color = symbolColor, start = Offset(cx + 0.5f * gS, cy), end = Offset(cx + gS, cy), strokeWidth = 3.5f * scale)

                // Plus (+) and minus (-) inside circle to detail polarization
                drawLine(color = symbolColor, start = Offset(cx - 0.3f * gS, cy), end = Offset(cx - 0.15f * gS, cy), strokeWidth = 2.5f * scale)
                drawLine(color = symbolColor, start = Offset(cx - 0.225f * gS, cy - 0.075f * gS), end = Offset(cx - 0.225f * gS, cy + 0.075f * gS), strokeWidth = 2.5f * scale)
                drawLine(color = symbolColor, start = Offset(cx + 0.15f * gS, cy), end = Offset(cx + 0.3f * gS, cy), strokeWidth = 2.5f * scale)

                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx - gS, cy))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx + gS, cy))

                drawContext.canvas.nativeCanvas.drawText(
                    comp.name,
                    cx - 0.55f * gS,
                    cy - 0.65f * gS,
                    android.graphics.Paint().apply {
                        color = if (isSelected) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                        textSize = 22f * scale
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
                
                val shortVal = if (comp.valueStr.contains("SINE", ignoreCase = true)) "AC Sine" 
                               else if (comp.valueStr.contains("PULSE", ignoreCase = true)) "AC Pulse"
                               else comp.valueStr
                drawContext.canvas.nativeCanvas.drawText(
                    shortVal,
                    cx - 0.75f * gS,
                    cy + 0.95f * gS,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.LTGRAY
                        textSize = 18f * scale
                    }
                )
            }
            ComponentType.CURRENT_SOURCE -> {
                // Circle current source
                drawLine(color = symbolColor, start = Offset(cx - gS, cy), end = Offset(cx - 0.5f * gS, cy), strokeWidth = 3.5f * scale)
                drawCircle(color = symbolColor, radius = 0.5f * gS, center = Offset(cx, cy), style = strokeStyle)
                drawLine(color = symbolColor, start = Offset(cx + 0.5f * gS, cy), end = Offset(cx + gS, cy), strokeWidth = 3.5f * scale)

                // Render arrow inside the circle pointing from left to right (current direction)
                drawLine(color = symbolColor, start = Offset(cx - 0.25f * gS, cy), end = Offset(cx + 0.25f * gS, cy), strokeWidth = 3f * scale)
                val arrowPath = Path().apply {
                    moveTo(cx + 0.25f * gS, cy)
                    lineTo(cx + 0.1f * gS, cy - 0.125f * gS)
                    lineTo(cx + 0.1f * gS, cy + 0.125f * gS)
                    close()
                }
                drawPath(path = arrowPath, color = symbolColor)

                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx - gS, cy))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx + gS, cy))

                drawContext.canvas.nativeCanvas.drawText(
                    comp.name,
                    cx - 0.5f * gS,
                    cy - 0.65f * gS,
                    android.graphics.Paint().apply {
                        color = if (isSelected) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                        textSize = 22f * scale
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
            }
            ComponentType.GROUND -> {
                // Ground triangle element
                drawLine(color = symbolColor, start = Offset(cx, cy), end = Offset(cx, cy + 0.35f * gS), strokeWidth = 3.5f * scale)
                
                // Horizontal segments getting smaller
                drawLine(color = symbolColor, start = Offset(cx - 0.45f * gS, cy + 0.35f * gS), end = Offset(cx + 0.45f * gS, cy + 0.35f * gS), strokeWidth = 3.5f * scale)
                drawLine(color = symbolColor, start = Offset(cx - 0.275f * gS, cy + 0.525f * gS), end = Offset(cx + 0.275f * gS, cy + 0.525f * gS), strokeWidth = 3.5f * scale)
                drawLine(color = symbolColor, start = Offset(cx - 0.1f * gS, cy + 0.7f * gS), end = Offset(cx + 0.1f * gS, cy + 0.7f * gS), strokeWidth = 3.5f * scale)

                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx, cy))

                drawContext.canvas.nativeCanvas.drawText(
                    "GND",
                    cx + 0.55f * gS,
                    cy + 0.55f * gS,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.LTGRAY
                        textSize = 18f * scale
                    }
                )
            }
            ComponentType.TRANSISTOR_NPN -> {
                drawLine(color = symbolColor, start = Offset(cx - 0.25f * gS, cy - 0.5f * gS), end = Offset(cx - 0.25f * gS, cy + 0.5f * gS), strokeWidth = 5f * scale)
                drawLine(color = symbolColor, start = Offset(cx - gS, cy), end = Offset(cx - 0.25f * gS, cy), strokeWidth = 3.5f * scale)
                
                val cPath = Path().apply {
                    moveTo(cx - 0.25f * gS, cy - 0.25f * gS)
                    lineTo(cx + 0.375f * gS, cy - 0.625f * gS)
                    lineTo(cx + 0.375f * gS, cy - gS)
                    lineTo(cx + gS, cy - gS)
                }
                drawPath(path = cPath, color = symbolColor, style = strokeStyle)

                val ePath = Path().apply {
                    moveTo(cx - 0.25f * gS, cy + 0.25f * gS)
                    lineTo(cx + 0.375f * gS, cy + 0.625f * gS)
                    lineTo(cx + 0.375f * gS, cy + gS)
                    lineTo(cx + gS, cy + gS)
                }
                drawPath(path = ePath, color = symbolColor, style = strokeStyle)

                val arrowPath = Path().apply {
                    moveTo(cx + 0.175f * gS, cy + 0.525f * gS)
                    lineTo(cx + 0.375f * gS, cy + 0.625f * gS)
                    lineTo(cx + 0.3f * gS, cy + 0.4f * gS)
                    close()
                }
                drawPath(path = arrowPath, color = symbolColor)

                // Render terminal pins
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx - gS, cy))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx + gS, cy - gS))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx + gS, cy + gS))

                drawContext.canvas.nativeCanvas.drawText(
                    comp.name,
                    cx - 0.75f * gS,
                    cy - 0.75f * gS,
                    android.graphics.Paint().apply {
                        color = if (isSelected) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                        textSize = 22f * scale
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
                drawContext.canvas.nativeCanvas.drawText(
                    comp.valueStr,
                    cx - 0.75f * gS,
                    cy + 1.3f * gS,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.LTGRAY
                        textSize = 19f * scale
                    }
                )
            }
            ComponentType.MOSFET_N -> {
                // Gate plate
                drawLine(color = symbolColor, start = Offset(cx - 0.375f * gS, cy - 0.5f * gS), end = Offset(cx - 0.375f * gS, cy + 0.5f * gS), strokeWidth = 5f * scale)
                // Gate lead
                drawLine(color = symbolColor, start = Offset(cx - gS, cy), end = Offset(cx - 0.375f * gS, cy), strokeWidth = 3.5f * scale)

                // Dynamic body/source channel plate
                drawLine(color = symbolColor, start = Offset(cx - 0.125f * gS, cy - 0.5f * gS), end = Offset(cx - 0.125f * gS, cy + 0.5f * gS), strokeWidth = 3.5f * scale)

                // Drain line
                drawLine(color = symbolColor, start = Offset(cx + gS, cy - gS), end = Offset(cx - 0.125f * gS, cy - gS), strokeWidth = 3.5f * scale)
                drawLine(color = symbolColor, start = Offset(cx - 0.125f * gS, cy - gS), end = Offset(cx - 0.125f * gS, cy - 0.5f * gS), strokeWidth = 3.5f * scale)

                // Source lead
                drawLine(color = symbolColor, start = Offset(cx + gS, cy + gS), end = Offset(cx - 0.125f * gS, cy + gS), strokeWidth = 3.5f * scale)
                drawLine(color = symbolColor, start = Offset(cx - 0.125f * gS, cy + gS), end = Offset(cx - 0.125f * gS, cy + 0.5f * gS), strokeWidth = 3.5f * scale)

                // Internal arrow pointing left from channel body
                val bodyArrow = Path().apply {
                    moveTo(cx - 0.375f * gS, cy)
                    lineTo(cx - 0.2f * gS, cy - 0.125f * gS)
                    lineTo(cx - 0.2f * gS, cy + 0.125f * gS)
                    close()
                }
                drawPath(path = bodyArrow, color = symbolColor)

                // Pins
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx - gS, cy))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx + gS, cy - gS))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx + gS, cy + gS))

                drawContext.canvas.nativeCanvas.drawText(
                    comp.name,
                    cx - 0.75f * gS,
                    cy - 0.75f * gS,
                    android.graphics.Paint().apply {
                        color = if (isSelected) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                        textSize = 22f * scale
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
            }
            ComponentType.THYRISTOR -> {
                // Diode Anode triangle and Cathode band (Anode left, Cathode right)
                drawLine(color = symbolColor, start = Offset(cx - gS, cy), end = Offset(cx - 0.35f * gS, cy), strokeWidth = 3.5f * scale)
                
                val path = Path().apply {
                    moveTo(cx - 0.35f * gS, cy - 0.375f * gS)
                    lineTo(cx + 0.35f * gS, cy)
                    lineTo(cx - 0.35f * gS, cy + 0.375f * gS)
                    close()
                }
                drawPath(path = path, color = symbolColor, style = Stroke(width = 3.5f * scale))
                drawLine(color = symbolColor, start = Offset(cx + 0.35f * gS, cy - 0.375f * gS), end = Offset(cx + 0.35f * gS, cy + 0.375f * gS), strokeWidth = 5f * scale)
                drawLine(color = symbolColor, start = Offset(cx + 0.35f * gS, cy), end = Offset(cx + gS, cy), strokeWidth = 3.5f * scale)

                // Gate connection drawing downwards
                drawLine(color = symbolColor, start = Offset(cx + 0.1f * gS, cy + 0.125f * gS), end = Offset(cx, cy + gS), strokeWidth = 3.5f * scale)

                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx - gS, cy))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx + gS, cy))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx, cy + gS))

                drawContext.canvas.nativeCanvas.drawText(
                    comp.name,
                    cx - 0.5f * gS,
                    cy - 0.6f * gS,
                    android.graphics.Paint().apply {
                        color = if (isSelected) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                        textSize = 22f * scale
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
            }
            ComponentType.TRIAC -> {
                val path1 = Path().apply {
                    moveTo(cx - 0.375f * gS, cy - 0.375f * gS)
                    lineTo(cx + 0.25f * gS, cy)
                    lineTo(cx - 0.375f * gS, cy + 0.375f * gS)
                    close()
                }
                val path2 = Path().apply {
                    moveTo(cx + 0.375f * gS, cy - 0.375f * gS)
                    lineTo(cx - 0.25f * gS, cy)
                    lineTo(cx + 0.375f * gS, cy + 0.375f * gS)
                    close()
                }
                drawPath(path = path1, color = symbolColor, style = Stroke(width = 3.5f * scale))
                drawPath(path = path2, color = symbolColor, style = Stroke(width = 3.5f * scale))

                drawLine(color = symbolColor, start = Offset(cx - gS, cy), end = Offset(cx - 0.25f * gS, cy), strokeWidth = 3.5f * scale)
                drawLine(color = symbolColor, start = Offset(cx + 0.25f * gS, cy), end = Offset(cx + gS, cy), strokeWidth = 3.5f * scale)

                // Gate contact line connects to the gate pin at (cx, cy + gS)
                drawLine(color = symbolColor, start = Offset(cx + 0.1f * gS, cy + 0.125f * gS), end = Offset(cx, cy + gS), strokeWidth = 3.5f * scale)

                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx - gS, cy))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx + gS, cy))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx, cy + gS))

                drawContext.canvas.nativeCanvas.drawText(
                    comp.name,
                    cx - 0.5f * gS,
                    cy - 0.6f * gS,
                    android.graphics.Paint().apply {
                        color = if (isSelected) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                        textSize = 22f * scale
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
            }
            ComponentType.RELAY -> {
                // Coil box on left side
                drawRect(
                    color = symbolColor,
                    topLeft = Offset(cx - 0.75f * gS, cy - 0.625f * gS),
                    size = Size(0.5f * gS, 1.25f * gS),
                    style = strokeStyle
                )
                
                // Coil leads to the actual pins
                drawLine(color = symbolColor, start = Offset(cx - gS, cy - gS), end = Offset(cx - 0.5f * gS, cy - gS), strokeWidth = 3.5f * scale)
                drawLine(color = symbolColor, start = Offset(cx - 0.5f * gS, cy - gS), end = Offset(cx - 0.5f * gS, cy - 0.45f * gS), strokeWidth = 3.5f * scale)
                
                drawLine(color = symbolColor, start = Offset(cx - gS, cy + gS), end = Offset(cx - 0.5f * gS, cy + gS), strokeWidth = 3.5f * scale)
                drawLine(color = symbolColor, start = Offset(cx - 0.5f * gS, cy + gS), end = Offset(cx - 0.5f * gS, cy + 0.45f * gS), strokeWidth = 3.5f * scale)

                // Switch leads to the actual pins
                drawLine(color = symbolColor, start = Offset(cx + gS, cy - gS), end = Offset(cx + 0.25f * gS, cy - gS), strokeWidth = 3.5f * scale)
                drawLine(color = symbolColor, start = Offset(cx + 0.25f * gS, cy - gS), end = Offset(cx + 0.25f * gS, cy - 0.375f * gS), strokeWidth = 3.5f * scale)
                
                drawLine(color = symbolColor, start = Offset(cx + gS, cy + gS), end = Offset(cx + 0.25f * gS, cy + gS), strokeWidth = 3.5f * scale)
                drawLine(color = symbolColor, start = Offset(cx + 0.25f * gS, cy + gS), end = Offset(cx + 0.25f * gS, cy + 0.375f * gS), strokeWidth = 3.5f * scale)

                // Switch pole arm (slanted representation)
                drawLine(color = symbolColor, start = Offset(cx + 0.25f * gS, cy - 0.375f * gS), end = Offset(cx + 0.75f * gS, cy + 0.2f * gS), strokeWidth = 3.5f * scale)

                // Render 4 terminals aligned with model pins
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx - gS, cy - gS))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx - gS, cy + gS))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx + gS, cy - gS))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx + gS, cy + gS))

                drawContext.canvas.nativeCanvas.drawText(
                    comp.name,
                    cx - 0.625f * gS,
                    cy - 0.75f * gS,
                    android.graphics.Paint().apply {
                        color = if (isSelected) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                        textSize = 22f * scale
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
            }
            ComponentType.OPAMP -> {
                // Triangle boundary
                val oPath = Path().apply {
                    moveTo(cx - 0.5f * gS, cy - 0.75f * gS)
                    lineTo(cx + 0.5f * gS, cy)
                    lineTo(cx - 0.5f * gS, cy + 0.75f * gS)
                    close()
                }
                drawPath(path = oPath, color = symbolColor, style = strokeStyle)

                // Input terminals
                drawLine(color = symbolColor, start = Offset(cx - gS, cy - gS), end = Offset(cx - 0.5f * gS, cy - gS), strokeWidth = 3.5f * scale)
                drawLine(color = symbolColor, start = Offset(cx - gS, cy + gS), end = Offset(cx - 0.5f * gS, cy + gS), strokeWidth = 3.5f * scale)

                // Output terminal
                drawLine(color = symbolColor, start = Offset(cx + 0.5f * gS, cy), end = Offset(cx + gS, cy), strokeWidth = 3.5f * scale)

                // Plus / Minus label markers inside triangle
                drawContext.canvas.nativeCanvas.drawText(
                    "+",
                    cx - 0.375f * gS,
                    cy - 0.25f * gS,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 20f * scale
                    }
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "-",
                    cx - 0.35f * gS,
                    cy + 0.55f * gS,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 22f * scale
                    }
                )

                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx - gS, cy - gS))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx - gS, cy + gS))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx + gS, cy))

                drawContext.canvas.nativeCanvas.drawText(
                    comp.name,
                    cx - 0.25f * gS,
                    cy - 0.9f * gS,
                    android.graphics.Paint().apply {
                        color = if (isSelected) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                        textSize = 22f * scale
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
            }
            ComponentType.SUBCIRCUIT -> {
                drawRect(color = symbolColor, topLeft = Offset(cx - gS, cy - gS), size = Size(2f * gS, 2f * gS), style = strokeStyle)

                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx - gS, cy - gS))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx - gS, cy + gS))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx + gS, cy - gS))
                drawCircle(color = Color(0xFFEF4444), radius = 3.5f * scale, center = Offset(cx + gS, cy + gS))

                drawContext.canvas.nativeCanvas.drawText(
                    comp.valueStr,
                    cx - 0.625f * gS,
                    cy - 0.875f * gS,
                    android.graphics.Paint().apply {
                        color = if (isSelected) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                        textSize = 18f * scale
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
                drawContext.canvas.nativeCanvas.drawText(
                    comp.name,
                    cx - 0.5f * gS,
                    cy + 0.2f * gS,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.CYAN
                        textSize = 20f * scale
                    }
                )
            }
            ComponentType.PORT -> {
                drawRect(color = symbolColor, topLeft = Offset(cx - 0.2f * gS, cy - 0.2f * gS), size = Size(0.4f * gS, 0.4f * gS), style = strokeStyle)
                drawCircle(color = Color(0xFFFFB4AB), radius = 3.5f * scale, center = Offset(cx, cy))
                drawContext.canvas.nativeCanvas.drawText(
                    comp.valueStr,
                    cx - 0.5f * gS,
                    cy - 0.3f * gS,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.CYAN
                        textSize = 17f * scale
                    }
                )
            }
        }
    }
}
