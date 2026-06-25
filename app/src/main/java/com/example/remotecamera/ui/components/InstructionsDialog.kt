package com.example.remotecamera.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.remotecamera.ui.main.DarkBg
import com.example.remotecamera.ui.main.GlassBorder
import com.example.remotecamera.ui.main.NeonCyan
import com.example.remotecamera.ui.main.TextPrimary
import com.example.remotecamera.ui.main.TextSecondary

@Composable
fun InstructionsDialog(
    title: String,
    instructions: List<String>,
    prefKey: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("app_instructions_prefs", Context.MODE_PRIVATE) }
    
    // Check if the user has opted out of seeing this instructions set
    val neverShowAgain = remember { sharedPrefs.getBoolean(prefKey, false) }
    if (neverShowAgain) {
        // Automatically dismiss if opt-out is stored
        LaunchedEffect(Unit) {
            onDismiss()
        }
        return
    }

    var neverShowChecked by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = DarkBg,
            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = title,
                    color = NeonCyan,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                instructions.forEachIndexed { index, step ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "${index + 1}. ",
                            color = NeonCyan,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = step,
                            color = TextPrimary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { neverShowChecked = !neverShowChecked }
                    ) {
                        Checkbox(
                            checked = neverShowChecked,
                            onCheckedChange = { neverShowChecked = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = NeonCyan,
                                uncheckedColor = TextSecondary,
                                checkmarkColor = Color.Black
                            ),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Never show again",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }

                    Button(
                        onClick = {
                            if (neverShowChecked) {
                                sharedPrefs.edit().putBoolean(prefKey, true).apply()
                            }
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "Got It",
                            color = Color(0xFF0F0F15),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}
