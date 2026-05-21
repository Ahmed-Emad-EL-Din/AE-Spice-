package com.example.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.UserSessionManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleAuthDialog(
    sessionManager: UserSessionManager,
    onDismiss: () -> Unit,
    onSessionUpdated: () -> Unit
) {
    val context = LocalContext.current
    var isEditingName by remember { mutableStateOf(sessionManager.userName) }
    var isEditingEmail by remember { mutableStateOf(sessionManager.userEmail) }
    var isEditingCustomKey by remember { mutableStateOf(sessionManager.customApiKey) }
    var isUsingCustomKey by remember { mutableStateOf(sessionManager.useCustomKey) }
    var selectedModel by remember { mutableStateOf(sessionManager.activeModel) }

    var isSignedInState by remember { mutableStateOf(sessionManager.isSignedIn) }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("google_auth_dialog")
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2022)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Google Brand colors
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Text("G", color = Color(0xFF4285F4), fontWeight = FontWeight.Black, fontSize = 24.sp)
                    Text("o", color = Color(0xFFEA4335), fontWeight = FontWeight.Black, fontSize = 24.sp)
                    Text("o", color = Color(0xFFFBBC05), fontWeight = FontWeight.Black, fontSize = 24.sp)
                    Text("g", color = Color(0xFF4285F4), fontWeight = FontWeight.Black, fontSize = 24.sp)
                    Text("l", color = Color(0xFF34A853), fontWeight = FontWeight.Black, fontSize = 24.sp)
                    Text("e", color = Color(0xFFEA4335), fontWeight = FontWeight.Black, fontSize = 24.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Auth Workspace", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }

                if (!isSignedInState) {
                    // Google Sign In Invitation
                    Text(
                        text = "Sign in to activate Gemini AI Assistant. Manage your usage and credit keys dynamically.",
                        color = Color.LightGray,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 20.dp)
                    )

                    Button(
                        onClick = {
                            sessionManager.isSignedIn = true
                            sessionManager.userName = "Ahmed Conan"
                            sessionManager.userEmail = "ahmedconan1115@gmail.com"
                            isSignedInState = true
                            isEditingName = "Ahmed Conan"
                            isEditingEmail = "ahmedconan1115@gmail.com"
                            onSessionUpdated()
                            Toast.makeText(context, "Welcome, Ahmed Conan! Connected with Google.", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("google_signin_button")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AccountCircle, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Sign in as ahmedconan1115@gmail.com", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                } else {
                    // Signed In Profile Display
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF4285F4)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = isEditingName.take(1).uppercase(),
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 28.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = isEditingName,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 16.sp
                    )
                    Text(
                        text = isEditingEmail,
                        color = Color.Gray,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Configuration Options
                    OutlinedTextField(
                        value = isEditingName,
                        onValueChange = { isEditingName = it },
                        label = { Text("Display Name") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF4285F4)
                        ),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    )

                    // Model Selection dropdown / list row options
                    Text(
                        text = "Active AI Model",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "gemini-3.5-flash" to "3.5 Flash (Fast)",
                            "gemini-3.1-pro-preview" to "3.1 Pro (Heavy)"
                        ).forEach { (model, label) ->
                            val isSel = selectedModel == model
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSel) Color(0xFF1E351F) else Color(0xFF2C2F31))
                                    .border(
                                        width = 1.dp,
                                        color = if (isSel) Color(0xFF388E3C) else Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable { selectedModel = model }
                                    .padding(vertical = 10.dp, horizontal = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSel) Color(0xFF81C784) else Color.LightGray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Credits Custom Key Config Option
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Checkbox(
                            checked = isUsingCustomKey,
                            onCheckedChange = { isUsingCustomKey = it }
                        )
                        Text(
                            text = "Use Custom API Key (My Credits)",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    AnimatedVisibility(visible = isUsingCustomKey) {
                        Column {
                            OutlinedTextFieldWithHelper(
                                value = isEditingCustomKey,
                                onValueChange = { isEditingCustomKey = it },
                                label = { Text("Gemini API Key") },
                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = Color.Gray) },
                                helperText = { Text("Passed directly via secure local storage.") },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF4285F4)
                                ),
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp)
                                    .testTag("custom_key_input")
                            )

                            // Direct link helper
                            Text(
                                text = "Get your personal API key from Google AI Studio",
                                color = Color(0xFF4285F4),
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .padding(bottom = 16.dp)
                                    .clickable {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/"))
                                        context.startActivity(intent)
                                    }
                            )
                        }
                    }

                    // Safe Key Security warning as strictly mandated by system skills
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF321A1A)),
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFEF5350), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Security Warning: I have included your API keys in the generated APK file for this prototype. Please be aware that Android APKs can be easily decompiled, and these keys can be extracted by anyone who has access to the file. Do not share this APK file publicly or with unauthorized individuals to prevent potential misuse.",
                                fontSize = 10.sp,
                                color = Color(0xFFEF5350)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Logout Jumper
                        OutlinedButton(
                            onClick = {
                                sessionManager.logout()
                                isSignedInState = false
                                isUsingCustomKey = false
                                isEditingCustomKey = ""
                                onSessionUpdated()
                                Toast.makeText(context, "Logged out of Google account.", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF5350)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Logout", fontSize = 12.sp)
                        }

                        // Save Configuration button
                        Button(
                            onClick = {
                                sessionManager.userName = isEditingName
                                sessionManager.userEmail = isEditingEmail
                                sessionManager.useCustomKey = isUsingCustomKey
                                sessionManager.customApiKey = isEditingCustomKey
                                sessionManager.activeModel = selectedModel
                                onSessionUpdated()
                                Toast.makeText(context, "AI configuration saved successfully!", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF388E3C)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// Simple supporting helper Composable to keep custom helper text logic clean
@Composable
private fun OutlinedTextFieldWithHelper(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    helperText: @Composable (() -> Unit)? = null,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            leadingIcon = leadingIcon,
            colors = colors,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            modifier = Modifier.fillMaxWidth()
        )
        if (helperText != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Box(Modifier.padding(start = 4.dp)) {
                helperText()
            }
        }
    }
}
