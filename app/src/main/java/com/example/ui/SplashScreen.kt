package com.example.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
    onDismiss: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }

    // Control animations and timer sequence
    LaunchedEffect(Unit) {
        visible = true
        delay(2500) // Keep branding visible for 2.5 seconds
        visible = false
        delay(1000) // Allow 1 second for professional smooth fade out
        onDismiss()
    }

    val alphaAnim by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 800, easing = LinearOutSlowInEasing),
        label = "Alpha"
    )

    val scaleAnim by animateFloatAsState(
        targetValue = if (visible) 1f else 0.94f,
        animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
        label = "Scale"
    )

    AnimatedVisibility(
        visible = visible || alphaAnim > 0.05f,
        enter = fadeIn(animationSpec = tween(500)),
        exit = fadeOut(animationSpec = tween(1000))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF030303)) // Classic solid true black background matching image
                .alpha(alphaAnim),
            contentAlignment = Alignment.Center
        ) {
            // Background electrical grid matrix
            Canvas(modifier = Modifier.fillMaxSize()) {
                val gridSpacing = 64f
                val strokeColor = Color(0xFF1F222B).copy(alpha = 0.35f)
                val strokeWidth = 1f

                // Draw vertical grid paths
                var x = 0f
                while (x < size.width) {
                    drawLine(
                        color = strokeColor,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = strokeWidth
                    )
                    x += gridSpacing
                }

                // Draw horizontal grid paths
                var y = 0f
                while (y < size.height) {
                    drawLine(
                        color = strokeColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = strokeWidth
                    )
                    y += gridSpacing
                }
            }

            // Central Branding Component Block
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .padding(24.dp)
                    .scale(scaleAnim)
            ) {
                // Main Header "AE spice"
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Golden "A" and white "E"
                    Row(
                        modifier = Modifier.padding(end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "A",
                            color = Color(0xFFFACC15), // Pure shining gold matching symbol
                            fontSize = 68.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.SansSerif
                        )
                        // Dynamic stylized white 'E' composed of three stacked futuristic trapezoids/bars
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                        ) {
                            Box(modifier = Modifier.size(width = 30.dp, height = 7.dp).background(Color.White))
                            Box(modifier = Modifier.size(width = 24.dp, height = 7.dp).background(Color.White))
                            Box(modifier = Modifier.size(width = 16.dp, height = 7.dp).background(Color.White))
                        }
                    }

                    // "spice" in stylized tech-modern font with a custom yellow dot
                    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                    Text(
                        text = "sp\u0131ce",
                        color = Color.White,
                        fontSize = 66.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = (-1.5).sp,
                        onTextLayout = { textLayoutResult = it },
                        modifier = Modifier.drawWithContent {
                            drawContent()
                            textLayoutResult?.let { layout ->
                                if (layout.layoutInput.text.length > 2) {
                                    val rect = layout.getBoundingBox(2)
                                    val dotRadius = 4.5.dp.toPx()
                                    val dotCenterX = rect.left + rect.width / 2f
                                    // Calculate the physical top of the lowercase stem (x-height)
                                    val stemTop = rect.top + rect.height * 0.34f
                                    // Place the dot center 4dp above the top of the stem
                                    val dotCenterY = stemTop - dotRadius - 4.dp.toPx()
                                    drawCircle(
                                        color = Color(0xFFFACC15), // Pure shining gold/yellow matching the 'A'
                                        radius = dotRadius,
                                        center = Offset(dotCenterX, dotCenterY)
                                    )
                                }
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Golden connecting wire bar with circle terminals and center resistor zig-zag
                Canvas(modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(24.dp)
                ) {
                    val midY = size.height / 2f
                    val goldColor = Color(0xFFFACC15)
                    
                    // Left Circle
                    drawCircle(color = goldColor, radius = 5.5f, center = Offset(12f, midY))
                    
                    // Left horizontal line
                    drawLine(
                        color = goldColor,
                        start = Offset(18f, midY),
                        end = Offset(size.width * 0.43f, midY),
                        strokeWidth = 3f,
                        cap = StrokeCap.Round
                    )
                    
                    // Center Resistor Zig-Zag
                    val rxStart = size.width * 0.43f
                    val rxEnd = size.width * 0.57f
                    val rWidth = rxEnd - rxStart
                    val rSegment = rWidth / 6f
                    
                    drawLine(color = goldColor, start = Offset(rxStart, midY), end = Offset(rxStart + rSegment, midY - 10f), strokeWidth = 3f, cap = StrokeCap.Round)
                    drawLine(color = goldColor, start = Offset(rxStart + rSegment, midY - 10f), end = Offset(rxStart + 3 * rSegment, midY + 10f), strokeWidth = 3f, cap = StrokeCap.Round)
                    drawLine(color = goldColor, start = Offset(rxStart + 3 * rSegment, midY + 10f), end = Offset(rxStart + 5 * rSegment, midY - 10f), strokeWidth = 3f, cap = StrokeCap.Round)
                    drawLine(color = goldColor, start = Offset(rxStart + 5 * rSegment, midY - 10f), end = Offset(rxEnd, midY), strokeWidth = 3f, cap = StrokeCap.Round)

                    // Right horizontal line
                    drawLine(
                        color = goldColor,
                        start = Offset(rxEnd, midY),
                        end = Offset(size.width - 18f, midY),
                        strokeWidth = 3f,
                        cap = StrokeCap.Round
                    )
                    
                    // Right Circle
                    drawCircle(color = goldColor, radius = 5.5f, center = Offset(size.width - 12f, midY))
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Subtitle 1: ELECTRONIC CIRCUIT SIMULATOR
                Text(
                    text = "ELECTRONIC CIRCUIT SIMULATOR",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Subtitle 2: MADE BY AHMED EMAD
                Text(
                    text = "MADE BY AHMED EMAD",
                    color = Color(0xFFFACC15),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.5.sp
                )
            }
        }
    }
}
