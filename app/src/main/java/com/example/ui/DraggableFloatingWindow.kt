package com.example.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun DraggableFloatingWindow(
    windowId: String,
    initialPosition: Offset,
    containerSize: IntSize,
    modifier: Modifier = Modifier,
    draggableBody: Boolean = false,
    onPositionChanged: (Offset) -> Unit = {},
    headerContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val windowManager = remember { WindowManager(context) }
    
    var offset by remember { mutableStateOf(windowManager.getPosition(windowId, initialPosition)) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(Unit) {
        onPositionChanged(offset)
    }

    val snapThreshold = with(LocalDensity.current) { 20.dp.toPx() }
    
    val dragModifier = Modifier.pointerInput(windowId) {
        detectDragGestures(
            onDragEnd = {
                // Apply snapping
                var targetX = offset.x
                var targetY = offset.y

                if (targetX < snapThreshold) targetX = 0f
                if (targetX > containerSize.width - size.width - snapThreshold) targetX = (containerSize.width - size.width).toFloat()

                if (targetY < snapThreshold) targetY = 0f
                if (targetY > containerSize.height - size.height - snapThreshold) targetY = (containerSize.height - size.height).toFloat()

                // Clamp to screen boundaries to prevent clipping
                targetX = targetX.coerceIn(0f, (containerSize.width - size.width).toFloat().coerceAtLeast(0f))
                targetY = targetY.coerceIn(0f, (containerSize.height - size.height).toFloat().coerceAtLeast(0f))

                offset = Offset(targetX, targetY)
                windowManager.savePosition(windowId, offset)
                onPositionChanged(offset)
            }
        ) { change, dragAmount ->
            change.consume()
            val currentX = offset.x + dragAmount.x
            val currentY = offset.y + dragAmount.y
            offset = Offset(currentX, currentY)
        }
    }

    Box(
        modifier = modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .onGloballyPositioned { coordinates ->
                size = coordinates.size
            }
            .then(if(draggableBody) dragModifier else Modifier)
            .pointerInput(Unit) {
                // Consume all touch events inside the window body to prevent leaks to canvas
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Main)
                        event.changes.forEach { 
                           if (!it.isConsumed) {
                               it.consume()
                           }
                        }
                    }
                }
            }
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
    ) {
        Column {
            // Header: draggable area
            if (headerContent != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if(!draggableBody) dragModifier else Modifier)
                ) {
                    headerContent()
                }
            }
            // Body
            Box(modifier = Modifier.weight(1f, fill = false)) {
                content()
            }
        }
    }
}
