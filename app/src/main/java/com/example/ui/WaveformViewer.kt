package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.SimResult
import java.util.Locale
import kotlin.math.abs

// Colors for waveforms
val WaveformColors = listOf(
    Color(0xFF92F7AD), // Elegant Mint Green
    Color(0xFFFFB4AB), // Elegant Soft Pink-Red
    Color(0xFFD1E4FF), // Elegant Slate Blue
    Color(0xFFFACC15), // Amber Gold
    Color(0xFFE040FB), // Electric Purple
    Color(0xFF00E676)  // Light Green
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WaveformViewer(
    result: SimResult,
    modifier: Modifier = Modifier,
    probedNodeToActivate: String? = null,
    onSelectNodeFromChart: (String) -> Unit = {}
) {
    val xUnit = if (result.xlabel.lowercase().contains("freq")) "Hz" else if (result.xlabel.lowercase().contains("op")) "" else "s"
    val xLabelPrefix = if (result.xlabel.lowercase().contains("freq")) "f" else if (result.xlabel.lowercase().contains("op")) "Value" else "t"

    if (result.timePoints.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "No plot data",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "No Waveform Data Available",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Run simulation, or tap wire nodes with Probe to visualize current or voltage curves.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
        return
    }

    // List of channels
    val availableVoltages = result.nodeVoltages.keys.toList()
    val availableCurrents = result.currents.keys.toList()
    val allChannels = (availableVoltages + availableCurrents)

    // Tracks which ones are selected for plotting (default select Node 1 and Node 2, or whatever exists except ground)
    val selectedChannels = remember(result) {
        val initial = mutableStateOf(
            allChannels.filter { it != "0 (GND)" }.take(2).toMutableStateList()
        )
        initial.value
    }

    LaunchedEffect(probedNodeToActivate) {
        probedNodeToActivate?.let { node ->
            if (allChannels.contains(node) && !selectedChannels.contains(node)) {
                selectedChannels.add(node)
            }
        }
    }

    // Active crosshair touch point (X coordinate index)
    var activeCursorIndex by remember { mutableStateOf<Int?>(null) }
    var touchXOffset by remember { mutableStateOf<Float?>(null) }

    var zoomX by remember(result) { mutableStateOf(1f) }
    var zoomY by remember(result) { mutableStateOf(1f) }
    var panX by remember(result) { mutableStateOf(0.0) }
    var panY by remember(result) { mutableStateOf(0.0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF141316))
            .padding(12.dp)
    ) {
        // --- 1. CHANNEL SELECTOR CHIPS ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Active Waveforms & Probe Diagnostics",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            
            if (zoomX != 1f || zoomY != 1f || panX != 0.0 || panY != 0.0) {
                TextButton(
                    onClick = {
                        zoomX = 1f
                        zoomY = 1f
                        panX = 0.0
                        panY = 0.0
                        activeCursorIndex = null
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset viewport zoom",
                        tint = Color(0xFFFACC15),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Reset Graph",
                        color = Color(0xFFFACC15),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            allChannels.forEachIndexed { idx, channel ->
                val color = WaveformColors[idx % WaveformColors.size]
                val isSelected = selectedChannels.contains(channel)
                
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        if (isSelected) {
                            selectedChannels.remove(channel)
                        } else {
                            selectedChannels.add(channel)
                        }
                        onSelectNodeFromChart(channel)
                    },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(color, RoundedCornerShape(2.dp))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(channel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = color.copy(alpha = 0.15f),
                        selectedLabelColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        }

        // --- 2. THE MAIN OSCILLOSCOPE CANVAS ---
        if (selectedChannels.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF070C19), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Select waveforms above to display plot grids.",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            }
        } else {
            // Layout scoping for drawing
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141316)) // Elegant Dark plot background
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val canvasWidth = constraints.maxWidth.toFloat()
                    val canvasHeight = constraints.maxHeight.toFloat()

                    // Padding inside the canvas for scales and text labels
                    val paddingLeft = 50.dp.value
                    val paddingRight = 16.dp.value
                    val paddingTop = 16.dp.value
                    val paddingBottom = 40.dp.value

                    val plotWidth = canvasWidth - paddingLeft - paddingRight
                    val plotHeight = canvasHeight - paddingTop - paddingBottom

                    // Multi-scaling calculation: Find min and max for active channels
                    var minVal = Double.MAX_VALUE
                    var maxVal = -Double.MAX_VALUE

                    selectedChannels.forEach { ch ->
                        val list = result.nodeVoltages[ch] ?: result.currents[ch] ?: emptyList()
                        list.forEach { v ->
                            if (v.isFinite()) {
                                if (v < minVal) minVal = v
                                if (v > maxVal) maxVal = v
                            }
                        }
                    }

                    // Sane bounds fallback
                    if (minVal == Double.MAX_VALUE || maxVal == -Double.MAX_VALUE || !minVal.isFinite() || !maxVal.isFinite()) {
                        minVal = -1.0
                        maxVal = 1.0
                    }

                    // Pad the limit so waveforms don't touch the graph boundaries
                    val diff = maxVal - minVal
                    val paddingFactor = if (diff == 0.0) 1.0 else diff * 0.1
                    val yMin = minVal - paddingFactor
                    val yMax = maxVal + paddingFactor

                    val tMin = result.timePoints.firstOrNull() ?: 0.0
                    val tMax = result.timePoints.lastOrNull() ?: 1.0
                    val tDiff = if (tMax == tMin) 1.0 else tMax - tMin

                    // Zoom and pan viewport constraints
                    val tCenterDefault = tMin + tDiff * 0.5
                    val tCenter = tCenterDefault + panX
                    val tHalfSpan = (tDiff * 0.5) / zoomX
                    val tLeft = if (tCenter.isFinite() && tHalfSpan.isFinite()) tCenter - tHalfSpan else tMin
                    val tRight = if (tCenter.isFinite() && tHalfSpan.isFinite()) tCenter + tHalfSpan else tMax
                    val tDiffCurrentRaw = tRight - tLeft
                    val tDiffCurrent = if (!tDiffCurrentRaw.isFinite() || tDiffCurrentRaw <= 1e-30) 1.0 else tDiffCurrentRaw

                    val yCenterDefault = yMin + (yMax - yMin) * 0.5
                    val yCenter = yCenterDefault + panY
                    val yHalfSpan = ((yMax - yMin) * 0.5) / zoomY
                    val yBottom = if (yCenter.isFinite() && yHalfSpan.isFinite()) yCenter - yHalfSpan else yMin
                    val yTop = if (yCenter.isFinite() && yHalfSpan.isFinite()) yCenter + yHalfSpan else yMax
                    val yDiffCurrentRaw = yTop - yBottom
                    val yDiffCurrent = if (!yDiffCurrentRaw.isFinite() || yDiffCurrentRaw <= 1e-30) 1.0 else yDiffCurrentRaw

                    val updateCursor = { pxX: Float ->
                        if (pxX in paddingLeft..(paddingLeft + plotWidth)) {
                            touchXOffset = pxX
                            val touchRatio = (pxX - paddingLeft) / plotWidth
                            val targetTime = tLeft + touchRatio * tDiffCurrent

                            var closestIdx = 0
                            var minTimeDiff = Double.MAX_VALUE
                            result.timePoints.forEachIndexed { index, time ->
                                val d = abs(time - targetTime)
                                if (d < minTimeDiff) {
                                    minTimeDiff = d
                                    closestIdx = index
                                }
                            }
                            activeCursorIndex = closestIdx
                        } else {
                            touchXOffset = null
                            activeCursorIndex = null
                        }
                    }

                    val clearCursor = {
                        touchXOffset = null
                        activeCursorIndex = null
                    }

                    // Remember updated state to prevent stale layout size captures in pointer input blocks
                    val currentPlotWidth by rememberUpdatedState(if (plotWidth > 0f) plotWidth else 1.0f)
                    val currentPlotHeight by rememberUpdatedState(if (plotHeight > 0f) plotHeight else 1.0f)
                    val currentTDiffCurrent by rememberUpdatedState(tDiffCurrent)
                    val currentYDiffCurrent by rememberUpdatedState(yDiffCurrent)
                    val currentUpdateCursor by rememberUpdatedState(updateCursor)

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(result) {
                                detectTransformGestures(panZoomLock = false) { _, pan, zoom, _ ->
                                    if (pan != Offset.Zero) {
                                        val pw = currentPlotWidth
                                        val ph = currentPlotHeight
                                        val td = currentTDiffCurrent
                                        val yd = currentYDiffCurrent
                                        val nextPanX = panX - (pan.x.toDouble() / pw) * td
                                        val nextPanY = panY + (pan.y.toDouble() / ph) * yd
                                        if (nextPanX.isFinite() && !nextPanX.isNaN()) {
                                            panX = nextPanX
                                        }
                                        if (nextPanY.isFinite() && !nextPanY.isNaN()) {
                                            panY = nextPanY
                                        }
                                    }
                                    if (zoom != 1f && zoom.isFinite() && !zoom.isNaN()) {
                                        val nextZoomX = (zoomX * zoom).coerceIn(0.1f, 100f)
                                        val nextZoomY = (zoomY * zoom).coerceIn(0.1f, 100f)
                                        if (nextZoomX.isFinite() && !nextZoomX.isNaN()) {
                                            zoomX = nextZoomX
                                        }
                                        if (nextZoomY.isFinite() && !nextZoomY.isNaN()) {
                                            zoomY = nextZoomY
                                        }
                                    }
                                }
                            }
                            .pointerInput(result) {
                                detectTapGestures(
                                    onTap = { offset ->
                                        currentUpdateCursor(offset.x)
                                    }
                                )
                            }
                    ) {
                        // A. Draw divisions / Grid lines
                        val gridLinesCount = 8
                        // Vertical grid lines (time)
                        for (i in 0..gridLinesCount) {
                            val ratio = i.toFloat() / gridLinesCount
                            val x = paddingLeft + ratio * plotWidth
                            drawLine(
                                color = Color(0xFF44474E),
                                start = Offset(x, paddingTop),
                                end = Offset(x, paddingTop + plotHeight),
                                strokeWidth = 1f
                            )
                        }
                        // Horizontal grid lines (volt/amp values)
                        val horizLinesCount = 6
                        for (i in 0..horizLinesCount) {
                            val ratio = i.toFloat() / horizLinesCount
                            val y = paddingTop + ratio * plotHeight
                            drawLine(
                                color = Color(0xFF44474E),
                                start = Offset(paddingLeft, y),
                                end = Offset(canvasWidth - paddingRight, y),
                                strokeWidth = 1f
                            )
                        }

                        // B. Draw Axes details
                        drawLine(
                            color = Color(0xFF44474E),
                            start = Offset(paddingLeft, paddingTop + plotHeight),
                            end = Offset(canvasWidth - paddingRight, paddingTop + plotHeight),
                            strokeWidth = 2f
                        )
                        drawLine(
                            color = Color(0xFF44474E),
                            start = Offset(paddingLeft, paddingTop),
                            end = Offset(paddingLeft, paddingTop + plotHeight),
                            strokeWidth = 2f
                        )

                        // C. Draw Waveforms
                        selectedChannels.forEach { ch ->
                            val chIdx = allChannels.indexOf(ch)
                            val color = WaveformColors[chIdx % WaveformColors.size]
                            val dataList = result.nodeVoltages[ch] ?: result.currents[ch] ?: emptyList()

                            if (dataList.isNotEmpty() && result.timePoints.size == dataList.size) {
                                val path = Path()
                                var isFirst = true

                                for (i in dataList.indices) {
                                    val tVal = result.timePoints[i]
                                    val dVal = dataList[i]

                                    if (tVal.isFinite() && dVal.isFinite()) {
                                        val xRatio = (tVal - tLeft) / tDiffCurrent
                                        val yRatio = (dVal - yBottom) / yDiffCurrent

                                        if (xRatio.isFinite() && yRatio.isFinite()) {
                                            val cX = paddingLeft + xRatio.toFloat() * plotWidth
                                            val cY = paddingTop + (1f - yRatio.toFloat()) * plotHeight

                                            if (cX.isFinite() && cY.isFinite()) {
                                                if (isFirst) {
                                                    path.moveTo(cX, cY)
                                                    isFirst = false
                                                } else {
                                                    path.lineTo(cX, cY)
                                                }
                                            }
                                        }
                                    }
                                }

                                clipRect(
                                    left = paddingLeft,
                                    top = paddingTop,
                                    right = paddingLeft + plotWidth,
                                    bottom = paddingTop + plotHeight
                                ) {
                                    drawPath(
                                        path = path,
                                        color = color,
                                        style = Stroke(width = 4f, cap = StrokeCap.Round)
                                    )
                                }
                            }
                        }

                        // D. Draw Axis Text Labels
                        // Y axis tags
                        for (i in 0..4) {
                            val ratio = i.toFloat() / 4
                            val tagVal = yBottom + ratio * yDiffCurrent
                            val y = paddingTop + (1f - ratio) * plotHeight
                            val formatVal = getEngineeringString(tagVal)

                            drawContext.canvas.nativeCanvas.drawText(
                                formatVal,
                                6f,
                                y + 4f,
                                android.graphics.Paint().apply {
                                    color = android.graphics.Color.GRAY
                                    textSize = 24f
                                    typeface = android.graphics.Typeface.MONOSPACE
                                }
                            )
                        }

                        // X axis tags (Time/Frequency intervals)
                        for (i in 0..4) {
                            val ratio = i.toFloat() / 4
                            val tagVal = tLeft + ratio * tDiffCurrent
                            val x = paddingLeft + ratio * plotWidth
                            val formatVal = formatEngUnit(tagVal, xUnit)

                            drawContext.canvas.nativeCanvas.drawText(
                                formatVal,
                                x - 25f,
                                paddingTop + plotHeight + 28f,
                                android.graphics.Paint().apply {
                                    color = android.graphics.Color.GRAY
                                    textSize = 24f
                                    typeface = android.graphics.Typeface.MONOSPACE
                                }
                            )
                        }

                        // E. Evaluate & Draw Interactive crosshair line
                        activeCursorIndex?.let { cursorIdx ->
                            if (cursorIdx in result.timePoints.indices) {
                                val actualTime = result.timePoints[cursorIdx]
                                val lineX = paddingLeft + ((actualTime - tLeft) / tDiffCurrent).toFloat() * plotWidth

                                if (lineX in paddingLeft..(paddingLeft + plotWidth)) {
                                    drawLine(
                                        color = Color.LightGray.copy(alpha = 0.5f),
                                        start = Offset(lineX, paddingTop),
                                        end = Offset(lineX, paddingTop + plotHeight),
                                        strokeWidth = 3f
                                    )

                                    drawCircle(
                                        color = Color.White,
                                        radius = 8f,
                                        center = Offset(lineX, paddingTop + plotHeight)
                                    )
                                }
                            }
                        }
                    }

                    // --- F. FLOATING DIAGNOSTICS HUD PANEL ---
                    val cursorIdx = activeCursorIndex
                    if (cursorIdx != null && cursorIdx in result.timePoints.indices) {
                        val cTime = result.timePoints[cursorIdx]
                        Card(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "$xLabelPrefix = ${formatEngUnit(cTime, xUnit)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                selectedChannels.forEach { ch ->
                                    val dataList = result.nodeVoltages[ch] ?: result.currents[ch] ?: emptyList()
                                    val chIdx = allChannels.indexOf(ch)
                                    val color = WaveformColors[chIdx % WaveformColors.size]
                                    val unit = if (ch.startsWith("I(")) "A" else "V"
                                    if (cursorIdx in dataList.indices) {
                                        val valAtT = dataList[cursorIdx]
                                        Text(
                                            text = "$ch: ${formatEngUnit(valAtT, unit)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            color = color
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Format double values with engineering multipliers
fun formatEngUnit(value: Double, unit: String): String {
    if (!value.isFinite()) return "${value} ${unit}"
    val absVal = abs(value)
    return when {
        absVal >= 1e9 -> String.format(Locale.ROOT, "%.2f G%s", value / 1e9, unit)
        absVal >= 1e6 -> String.format(Locale.ROOT, "%.2f M%s", value / 1e6, unit)
        absVal >= 1e3 -> String.format(Locale.ROOT, "%.2f k%s", value / 1e3, unit)
        absVal >= 1.0 -> String.format(Locale.ROOT, "%.2f %s", value, unit)
        absVal >= 1e-3 -> String.format(Locale.ROOT, "%.2f m%s", value * 1e3, unit)
        absVal >= 1e-6 -> String.format(Locale.ROOT, "%.2f μ%s", value * 1e6, unit)
        absVal >= 1e-9 -> String.format(Locale.ROOT, "%.2f n%s", value * 1e9, unit)
        absVal >= 1e-12 -> String.format(Locale.ROOT, "%.2f p%s", value * 1e12, unit)
        absVal == 0.0 -> String.format(Locale.ROOT, "0 %s", unit)
        else -> String.format(Locale.ROOT, "%.2e %s", value, unit)
    }
}

fun getEngineeringString(value: Double): String {
    if (!value.isFinite()) return value.toString()
    val absVal = abs(value)
    return when {
         absVal >= 1e6 -> String.format(Locale.ROOT, "%.1fM", value / 1e6)
         absVal >= 1e3 -> String.format(Locale.ROOT, "%.1fk", value / 1e3)
         absVal >= 1.0 -> String.format(Locale.ROOT, "%.1f", value)
         absVal >= 1e-3 -> String.format(Locale.ROOT, "%.1fm", value * 1e3)
         absVal >= 1e-6 -> String.format(Locale.ROOT, "%.1fu", value * 1e6)
         absVal >= 1e-9 -> String.format(Locale.ROOT, "%.1fn", value * 1e9)
         else -> String.format(Locale.ROOT, "%.0e", value)
    }
}
