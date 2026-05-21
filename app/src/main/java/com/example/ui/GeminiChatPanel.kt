package com.example.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.*
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatMessage(
    val id: String,
    val text: String,
    val isUser: Boolean,
    val proposedAction: ProposedCircuitAction? = null
)

sealed class ProposedCircuitAction {
    data class ReplaceCircuit(val components: List<Component>, val wires: List<Wire>) : ProposedCircuitAction()
    data class ModifyParameters(val modifications: List<ParameterMod>) : ProposedCircuitAction()
    data class RunSimulation(val settings: SimulationSettings) : ProposedCircuitAction()
}

data class ParameterMod(val name: String, val valueStr: String)

// Moshi Models for AI Actions
@JsonClass(generateAdapter = true)
data class AiActionJson(
    val type: String? = null,
    val components: List<AiComponentJson>? = null,
    val wires: List<AiWireJson>? = null,
    val modifications: List<AiModJson>? = null,
    val settings: AiSettingsJson? = null
)

@JsonClass(generateAdapter = true)
data class AiComponentJson(
    val id: String? = null,
    val type: String? = null,
    val name: String? = null,
    val valueStr: String? = null,
    val gridX: Int? = null,
    val gridY: Int? = null,
    val orientation: String? = null
)

@JsonClass(generateAdapter = true)
data class AiWireJson(
    val startX: Int? = null,
    val startY: Int? = null,
    val endX: Int? = null,
    val endY: Int? = null
)

@JsonClass(generateAdapter = true)
data class AiModJson(
    val name: String? = null,
    val valueStr: String? = null
)

@JsonClass(generateAdapter = true)
data class AiSettingsJson(
    val type: String? = null,
    val stopTimeStr: String? = null,
    val stepTimeStr: String? = null
)

@Composable
fun GeminiChatPanel(
    components: List<Component>,
    wires: List<Wire>,
    simSettings: SimulationSettings,
    simResult: SimResult?,
    onReplaceCircuit: (List<Component>, List<Wire>) -> Unit,
    onModifyParameters: (List<ParameterMod>) -> Unit,
    onRunSimulation: (SimulationSettings) -> Unit,
    onShowSettings: () -> Unit,
    sessionManager: UserSessionManager
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current

    var messages by remember {
        mutableStateOf(
            listOf(
                ChatMessage(
                    id = "welcome",
                    text = "Hello! I am Gemini. I can help you draw schematic circuits, modify device parameters, and even execute and analyze SPICE simulations. Try asking me to \"Design a voltage divider\" or \"Create a low pass filter and run simulation\".",
                    isUser = false
                )
            )
        )
    }

    var textInput by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }

    val moshi = remember {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }
    val actionAdapter = remember { moshi.adapter(AiActionJson::class.java) }

    // Resilient json extractor
    fun parseProposal(text: String): ProposedCircuitAction? {
        try {
            // Find code blocks containing JSON
            val blockStart = text.indexOf("```json")
            val jsonText = if (blockStart != -1) {
                val sub = text.substring(blockStart + 7)
                val blockEnd = sub.indexOf("```")
                if (blockEnd != -1) sub.substring(0, blockEnd).trim() else sub.trim()
            } else {
                // Look for standard matching braces
                val firstBrace = text.indexOf('{')
                val lastBrace = text.lastIndexOf('}')
                if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
                    text.substring(firstBrace, lastBrace + 1).trim()
                } else {
                    return null
                }
            }

            val parsed = actionAdapter.fromJson(jsonText) ?: return null
            return when (parsed.type?.lowercase()?.trim()) {
                "replace_circuit" -> {
                    val comps = parsed.components?.mapNotNull { c ->
                        val type = try { ComponentType.valueOf(c.type ?: "RESISTOR") } catch (e: Exception) { null }
                        val orient = try { Orientation.valueOf(c.orientation ?: "DEG_0") } catch (e: Exception) { Orientation.DEG_0 }
                        if (type != null && c.name != null) {
                            Component(
                                id = c.id ?: "${type.name}_${System.currentTimeMillis()}",
                                type = type,
                                name = c.name,
                                valueStr = c.valueStr ?: "1k",
                                gridX = c.gridX ?: 0,
                                gridY = c.gridY ?: 0,
                                orientation = orient
                            )
                        } else null
                    } ?: emptyList()

                    val wrs = parsed.wires?.mapIndexed { idx, w ->
                        Wire(
                            id = "W_AI_${System.currentTimeMillis()}_$idx",
                            start = GridPoint(w.startX ?: 0, w.startY ?: 0),
                            end = GridPoint(w.endX ?: 0, w.endY ?: 0)
                        )
                    } ?: emptyList()

                    if (comps.isNotEmpty() || wrs.isNotEmpty()) {
                        ProposedCircuitAction.ReplaceCircuit(comps, wrs)
                    } else null
                }
                "modify_parameters" -> {
                    val mods = parsed.modifications?.mapNotNull { m ->
                        if (m.name != null && m.valueStr != null) {
                            ParameterMod(m.name, m.valueStr)
                        } else null
                    } ?: emptyList()

                    if (mods.isNotEmpty()) {
                        ProposedCircuitAction.ModifyParameters(mods)
                    } else null
                }
                "run_simulation" -> {
                    val mode = try { SimType.valueOf(parsed.settings?.type ?: "TRANSIENT") } catch (e: Exception) { SimType.TRANSIENT }
                    val settings = SimulationSettings(
                        type = mode,
                        stopTimeStr = parsed.settings?.stopTimeStr ?: "10m",
                        stepTimeStr = parsed.settings?.stepTimeStr ?: "0.1m"
                    )
                    ProposedCircuitAction.RunSimulation(settings)
                }
                else -> null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun makePromptContext(): String {
        val schemaJson = buildString {
            append("{\n  \"components\": [\n")
            components.forEachIndexed { i, c ->
                append("    {\"id\": \"${c.id}\", \"type\": \"${c.type.name}\", \"name\": \"${c.name}\", \"valueStr\": \"${c.valueStr}\", \"gridX\": ${c.gridX}, \"gridY\": ${c.gridY}, \"orientation\": \"${c.orientation.name}\"}")
                if (i < components.size - 1) append(",")
                append("\n")
            }
            append("  ],\n  \"wires\": [\n")
            wires.forEachIndexed { i, w ->
                append("    {\"startX\": ${w.start.x}, \"startY\": ${w.start.y}, \"endX\": ${w.end.x}, \"endY\": ${w.end.y}}")
                if (i < wires.size - 1) append(",")
                append("\n")
            }
            append("  ]\n}")
        }

        val resultContext = if (simResult != null) {
            "Simulation Success. Nodes evaluated: ${simResult.nodeVoltages.keys.joinToString(", ")}. Variables length = ${simResult.timePoints.size} data points. Active curves are readable."
        } else {
            "No active simulation run results available yet."
        }

        return """
You are a brilliant SPICE Simulation and Electronic Design assistant helping the user design, draw, and modify schematics on a grid.
Grid spacing is generally from X=-15 to 15, Y=-15 to 15.

The current schematic layout is:
$schemaJson

Current Simulation Type: ${simSettings.type.name}
Simulation Settings: stopTime=${simSettings.stopTimeStr}, stepTime=${simSettings.stepTimeStr}
Simulation Run Diagnostic: $resultContext

Component properties configurations rules:
1. Two-pin components (RESISTOR, CAPACITOR, INDUCTOR, DIODE, VOLTAGE_SOURCE, CURRENT_SOURCE) span 2 grid sizes horizontally or vertically. To connect, attach wires to their pins.
A components pins are translated relative to its gridX/Y. Two pins are usually at local coordinates (-1, 0) and (1, 0) (unrotated).
For GROUND, there is a single pin at its grid position. All active circuits must connect to GROUND to solve successfully (Ground node ID is always 0 / GND).
PORT pin is at its grid position.
TRANSISTOR_NPN pins are Base (-1, 0), Collector (1, -1), and Emitter (1, 1).
MOSFET_N pins are Gate (-1, 0), Drain (1, -1), and Source (1, 1).
OPAMP (UA741 / active op-amp icon): Inverting (-) (-1, -1), Non-Inverting (+) (-1, 1), and Output (1, 0).

You can suggest and build circuits dynamically! If you want to DRAW, MODIFY, or RUN a simulation, format your reply with your regular dialogue detailing the electronics theory, AND include ONE special JSON code block matching the following schemas.

Schema A (REPLACE ENTIRE SCHEMATIC OR GENERATE BRAND NEW CIRCUIT):
```json
{
  "type": "replace_circuit",
  "components": [
    {"id": "R1", "type": "RESISTOR", "name": "R1", "valueStr": "100", "gridX": -2, "gridY": 0, "orientation": "DEG_0"},
    {"id": "V1", "type": "VOLTAGE_SOURCE", "name": "V1", "valueStr": "SINE(0 10 1k)", "gridX": -6, "gridY": 0, "orientation": "DEG_90"},
    {"id": "GND1", "type": "GROUND", "name": "GND1", "valueStr": "0", "gridX": -6, "gridY": 2, "orientation": "DEG_0"}
  ],
  "wires": [
    {"startX": -6, "startY": -1, "endX": -3, "endY": -1},
    {"startX": -1, "startY": 0, "endX": 2, "endY": 0}
  ]
}
```

Schema B (MODIFY DEVICE PARAMETERS INDIVIDUALLY):
```json
{
  "type": "modify_parameters",
  "modifications": [
    {"name": "R1", "valueStr": "2.2k"},
    {"name": "C1", "valueStr": "47u"}
  ]
}
```

Schema C (RUN ACTIVE SPICE SOLVER):
```json
{
  "type": "run_simulation",
  "settings": {
    "type": "TRANSIENT",
    "stopTimeStr": "50m",
    "stepTimeStr": "0.1m"
  }
}
```

Ensure wires align exactly with component pin locations (usually -1 or +1 offsets from component coordinates) so user's schematic is connected. Keep your design theory concise and friendly!
"""
    }

    fun sendMessage() {
        val userText = textInput.trim()
        if (userText.isBlank() || isSending) return

        val userMsgId = "msg_user_${System.currentTimeMillis()}"
        val currentInput = textInput
        messages = messages + ChatMessage(id = userMsgId, text = currentInput, isUser = true)
        textInput = ""
        isSending = true
        keyboardController?.hide()

        // Scroll to bottom
        scope.launch {
            scrollState.animateScrollToItem(messages.size)
        }

        scope.launch {
            try {
                val apiKey = sessionManager.getEffectiveApiKey()
                if (apiKey.isBlank()) {
                    messages = messages + ChatMessage(
                        id = "msg_error_${System.currentTimeMillis()}",
                        text = "⚠️ Gemini API key is missing. Please click \"Google Auth Workspace\" at the top to configure credits or key settings.",
                        isUser = false
                    )
                    return@launch
                }

                val currentHistory = messages.joinToString("\n") {
                    if (it.isUser) "User: ${it.text}" else "Gemini: ${it.text}"
                }

                val systemInstructionStr = makePromptContext()

                val apiRequest = GeminiRequest(
                    contents = listOf(
                        GeminiContent(
                            parts = listOf(
                                GeminiPart(text = "Previous conversation history context:\n$currentHistory\n\nUser request: $currentInput")
                            )
                        )
                    ),
                    systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = systemInstructionStr)))
                )

                val response = withContext(Dispatchers.IO) {
                    GeminiClient.api.generateContent(
                        model = sessionManager.activeModel,
                        apiKey = apiKey,
                        request = apiRequest
                    )
                }

                val resultText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    ?: "Could not establish Gemini connection. Please verify API credits limits."

                val parsedAction = parseProposal(resultText)

                messages = messages + ChatMessage(
                    id = "msg_ai_${System.currentTimeMillis()}",
                    text = resultText,
                    isUser = false,
                    proposedAction = parsedAction
                )
            } catch (e: Exception) {
                e.printStackTrace()
                messages = messages + ChatMessage(
                    id = "msg_err_${System.currentTimeMillis()}",
                    text = "Error connecting to Gemini API on your personal credits: ${e.localizedMessage ?: "timeout. Please check network state"}.",
                    isUser = false
                )
            } finally {
                isSending = false
                scope.launch {
                    scrollState.animateScrollToItem(messages.size)
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF141517))
            .testTag("gemini_chat_panel")
    ) {
        // Simple User Header integration showing Google account status and credit preferences
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2022)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (sessionManager.isSignedIn) Color(0xFF1A73E8) else Color(0xFF5A5C60)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (sessionManager.isSignedIn) {
                            Text(
                                text = sessionManager.userName.take(1).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 16.sp
                            )
                        } else {
                            Icon(Icons.Default.AccountCircle, contentDescription = null, tint = Color.LightGray)
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = if (sessionManager.isSignedIn) sessionManager.userName else "Guest Sandbox Mode",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color.White
                        )
                        Text(
                            text = if (sessionManager.useCustomKey) "Personal API Credits Active" else "Default Platform Credits Mode",
                            fontSize = 11.sp,
                            color = Color(0xFFA1A3A5)
                        )
                    }
                }

                IconButton(
                    onClick = { onShowSettings() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Configure credit services",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Quick action presets for instant prompts
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val suggestions = listOf(
                "Design voltage divider" to "Design a voltage divider circuit with R1=1k and R2=2.2k using SINE generator V1 and GROUND.",
                "Build low pass filter" to "Design an RC Low Pass Filter schematic with R=100 and C=10u"
            )

            suggestions.forEach { (label, prompt) ->
                Button(
                    onClick = {
                        textInput = prompt
                        sendMessage()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2C2F33),
                        contentColor = Color.LightGray
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier
                        .height(32.dp)
                        .weight(1f)
                ) {
                    Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Divider(color = Color(0xFF232527), thickness = 1.dp)

        // Chat List
        LazyColumn(
            state = scrollState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messages) { message ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
                    ) {
                        if (!message.isUser) {
                            Box(
                                modifier = Modifier
                                    .padding(end = 8.dp, top = 4.dp)
                                    .size(24.dp)
                                    .background(Color(0xFF2E7D32), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("G", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }

                        // Message bubble
                        Box(
                            modifier = Modifier
                                .widthIn(max = 290.dp)
                                .clip(
                                    RoundedCornerShape(
                                        topStart = 16.dp,
                                        topEnd = 16.dp,
                                        bottomStart = if (message.isUser) 16.dp else 4.dp,
                                        bottomEnd = if (message.isUser) 4.dp else 16.dp
                                    )
                                )
                                .background(if (message.isUser) Color(0xFF1A73E8) else Color(0xFF2C2D30))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = message.text,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.SansSerif,
                                lineHeight = 18.sp
                            )
                        }
                    }

                    // Proposed Action Widget Block
                    message.proposedAction?.let { action ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Card(
                            modifier = Modifier
                                .padding(start = if (message.isUser) 0.dp else 32.dp)
                                .widthIn(max = 280.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E351F)),
                            border = BorderStroke(1.dp, Color(0xFF388E3C)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        tint = Color(0xFF81C784),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "AI Proposed Action",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = Color(0xFF81C784)
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                val label = when (action) {
                                    is ProposedCircuitAction.ReplaceCircuit -> "Build proposed circuit schematic onto workspace"
                                    is ProposedCircuitAction.ModifyParameters -> "Modify parameter values for components: " + action.modifications.joinToString(", ") { "${it.name}=${it.valueStr}" }
                                    is ProposedCircuitAction.RunSimulation -> "Configure & Run SPICE Transient Simulation"
                                }

                                Text(text = label, fontSize = 11.sp, color = Color.LightGray)

                                Spacer(modifier = Modifier.height(8.dp))

                                Button(
                                    onClick = {
                                        when (action) {
                                            is ProposedCircuitAction.ReplaceCircuit -> {
                                                onReplaceCircuit(action.components, action.wires)
                                                Toast.makeText(context, "Circuit schematic successfully updated!", Toast.LENGTH_SHORT).show()
                                            }
                                            is ProposedCircuitAction.ModifyParameters -> {
                                                onModifyParameters(action.modifications)
                                                Toast.makeText(context, "Component parameters successfully modified!", Toast.LENGTH_SHORT).show()
                                            }
                                            is ProposedCircuitAction.RunSimulation -> {
                                                onRunSimulation(action.settings)
                                                Toast.makeText(context, "Running AI requested simulation sweep!", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    modifier = Modifier
                                        .height(32.dp)
                                        .align(Alignment.End),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Apply Changes", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Send Controls Block
        Column(
            modifier = Modifier
                .background(Color(0xFF1E2021))
                .padding(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("Talk with Gemini of AI Studio...", fontSize = 13.sp, color = Color.Gray) },
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF2C2F31))
                        .testTag("gemini_prompt_input"),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF2C2F31),
                        unfocusedContainerColor = Color(0xFF2C2F31),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendMessage() })
                )

                Spacer(modifier = Modifier.width(6.dp))

                IconButton(
                    onClick = { sendMessage() },
                    enabled = textInput.isNotBlank() && !isSending,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (textInput.isNotBlank()) MaterialTheme.colorScheme.primary else Color(0xFF2C2F31))
                ) {
                    if (isSending) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send",
                            tint = if (textInput.isNotBlank()) Color.White else Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // AI Studio security disclaimer requirement
            Text(
                text = "⚠️ Security Warning: Keys are secured through local encryption in sandbox credits execution.",
                fontSize = 9.sp,
                color = Color.DarkGray,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}
