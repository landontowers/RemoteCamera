package com.example.remotecamera.ui.controller

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwitchCamera
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.remotecamera.ui.components.InstructionsDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.remotecamera.net.ControllerConnection
import com.example.remotecamera.net.NsdHelper
import com.example.remotecamera.ui.main.DarkBg
import com.example.remotecamera.ui.main.GlassBg
import com.example.remotecamera.ui.main.GlassBorder
import com.example.remotecamera.ui.main.NeonCyan
import com.example.remotecamera.ui.main.NeonPurple
import com.example.remotecamera.ui.main.TextPrimary
import com.example.remotecamera.ui.main.TextSecondary

@Composable
fun ControllerModeScreen(
  controllerConnection: ControllerConnection,
  onGalleryClick: () -> Unit,
  onBackClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current

  // Intercept back button to disconnect socket when leaving
  BackHandler {
    controllerConnection.disconnect()
    onBackClick()
  }

  // 1. Connection states
  val connectionState by controllerConnection.connectionStatus.collectAsState()
  val viewfinderBitmap by controllerConnection.viewfinderState.collectAsState()
  val cameraStatus by controllerConnection.cameraStatus.collectAsState()
  val availableCameras by controllerConnection.availableCameras.collectAsState()
  val activeCameraIndex by controllerConnection.activeCameraIndex.collectAsState()
  val zoomLimits by controllerConnection.zoomLimits.collectAsState()
  val currentZoom by controllerConnection.currentZoom.collectAsState()
  val batteryLevel by controllerConnection.batteryLevel.collectAsState()
  val isCharging by controllerConnection.isCharging.collectAsState()
  val diskSpace by controllerConnection.diskSpace.collectAsState()
  val isTorchOn by controllerConnection.isTorchOn.collectAsState()

  var showInstructions by remember { mutableStateOf(false) }

  // Trigger instructions dialog when successfully connected
  LaunchedEffect(connectionState) {
    if (connectionState is ControllerConnection.ConnectionState.Connected) {
      showInstructions = true
    }
  }

  // 2. Discover mDNS service automatically
  var discoveryMessage by remember { mutableStateOf("Initializing mDNS discovery...") }
  val nsdHelper = remember { NsdHelper(context) }

  DisposableEffect(Unit) {
    if (controllerConnection.connectionStatus.value == ControllerConnection.ConnectionState.Disconnected) {
      nsdHelper.onDiscoveryStarted = {
        discoveryMessage = "Scanning for Camera Server on Wi-Fi Hotspot..."
      }
      nsdHelper.onServiceResolved = { ip, port ->
        discoveryMessage = "Camera Server resolved! Connecting to $ip:$port"
        controllerConnection.connect(ip, port)
      }
      nsdHelper.onError = { error ->
        discoveryMessage = "mDNS Discovery error: $error"
      }
      nsdHelper.discoverServices()
    } else {
      discoveryMessage = "Reconnected to active camera stream."
    }

    onDispose {
      nsdHelper.stopDiscovery()
    }
  }

  // Pulsing animation for the recording dot
  val infiniteTransition = rememberInfiniteTransition(label = "pulse")
  val alphaPulse by infiniteTransition.animateFloat(
    initialValue = 0.2f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(800, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "recPulse"
  )

  // Map incoming string status events
  val isRecording = cameraStatus.startsWith("RECORD_STARTED") || cameraStatus.startsWith("STATUS:RECORD_STARTED")
  val displayStatus = when {
    isRecording -> "RECORDING"
    cameraStatus.startsWith("RECORD_FINISHED") -> "Recording Saved"
    cameraStatus.startsWith("PHOTO_SUCCESS") -> "Photo Saved!"
    cameraStatus.startsWith("PHOTO_ERROR") -> "Photo Capture Failed"
    cameraStatus.startsWith("RECORD_ERROR") -> "Recording Failed"
    else -> cameraStatus
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(DarkBg)
      .padding(16.dp),
    verticalArrangement = Arrangement.SpaceBetween
  ) {
    // Toolbar
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = 24.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
          controllerConnection.disconnect()
          onBackClick()
        }) {
          Icon(
            imageVector = Icons.Default.ArrowBack,
            contentDescription = "Back",
            tint = TextPrimary
          )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
          Text(
            text = "Remote Viewfinder",
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = when (connectionState) {
              is ControllerConnection.ConnectionState.Connected -> "Connected"
              is ControllerConnection.ConnectionState.Connecting -> "Connecting..."
              is ControllerConnection.ConnectionState.Error -> "Connection Error"
              else -> "Offline"
            },
            color = when (connectionState) {
              is ControllerConnection.ConnectionState.Connected -> Color(0xFF00C853)
              is ControllerConnection.ConnectionState.Connecting -> NeonCyan
              is ControllerConnection.ConnectionState.Error -> Color.Red
              else -> TextSecondary
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
          )
        }
      }

      // Telemetry info shown when connected
      if (connectionState is ControllerConnection.ConnectionState.Connected) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          // Disk Space
          if (diskSpace.isNotEmpty()) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(GlassBg)
                .border(1.dp, GlassBorder, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
              Icon(
                imageVector = Icons.Default.Storage,
                contentDescription = "Storage",
                tint = NeonCyan,
                modifier = Modifier.size(12.dp)
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = diskSpace.replace(" free", ""),
                color = TextPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
              )
            }
          }

          // Battery
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
              .clip(RoundedCornerShape(8.dp))
              .background(GlassBg)
              .border(1.dp, GlassBorder, RoundedCornerShape(8.dp))
              .padding(horizontal = 8.dp, vertical = 4.dp)
          ) {
            Icon(
              imageVector = if (isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryStd,
              contentDescription = "Battery",
              tint = if (batteryLevel < 20) Color.Red else Color(0xFF00C853),
              modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = "$batteryLevel%",
              color = TextPrimary,
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold
            )
          }
        }
      }
    }

    // Viewfinder
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .padding(vertical = 8.dp),
      contentAlignment = Alignment.Center
    ) {
      Card(
        modifier = Modifier
          .fillMaxHeight()
          .aspectRatio(3f / 4f, matchHeightConstraintsFirst = true)
          .clip(RoundedCornerShape(20.dp))
          .border(1.dp, GlassBorder, RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = GlassBg)
      ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          if (connectionState is ControllerConnection.ConnectionState.Connected && viewfinderBitmap != null) {
            Image(
              bitmap = viewfinderBitmap!!.asImageBitmap(),
              contentDescription = "Viewfinder Stream",
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize()
            )

            // REC Status Overlay on top left of stream
            if (isRecording) {
              Row(
                modifier = Modifier
                  .align(Alignment.TopStart)
                  .padding(16.dp)
                  .clip(RoundedCornerShape(8.dp))
                  .background(Color.Black.copy(alpha = 0.6f))
                  .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Icon(
                  imageVector = Icons.Default.FiberManualRecord,
                  contentDescription = "Rec Dot",
                  tint = Color.Red,
                  modifier = Modifier
                    .size(12.dp)
                    .alpha(alphaPulse)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                  text = "REC",
                  color = Color.Red,
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Bold
                )
              }
            }

            // Lens status banner on top right
            Box(
              modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
              Text(
                text = displayStatus,
                color = if (isRecording) Color.Red else NeonCyan,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
              )
            }
          } else {
            // Placeholder state while scanning/connecting
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.Center,
              modifier = Modifier.padding(24.dp)
            ) {
              if (connectionState is ControllerConnection.ConnectionState.Connecting) {
                CircularProgressIndicator(
                  color = NeonCyan,
                  strokeWidth = 3.dp,
                  modifier = Modifier.size(48.dp)
                )
              } else {
                Icon(
                  imageVector = if (connectionState is ControllerConnection.ConnectionState.Error) Icons.Default.WifiOff else Icons.Default.Wifi,
                  contentDescription = "P2P Status",
                  tint = if (connectionState is ControllerConnection.ConnectionState.Error) Color.Red else NeonPurple,
                  modifier = Modifier
                    .size(48.dp)
                    .alpha(alphaPulse)
                )
              }
              Spacer(modifier = Modifier.height(16.dp))
              Text(
                text = when (connectionState) {
                  is ControllerConnection.ConnectionState.Connecting -> "Connecting to Lens Server..."
                  is ControllerConnection.ConnectionState.Error -> "Connection Failed\nResolving..."
                  else -> "Scanning for mDNS broadcast..."
                },
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
              )
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                text = discoveryMessage,
                color = TextSecondary,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                lineHeight = 15.sp
              )
            }
          }
        }
      }
    }

    // Trigger Action Controls
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(24.dp))
        .border(1.dp, GlassBorder, RoundedCornerShape(24.dp)),
      colors = CardDefaults.cardColors(containerColor = GlassBg)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        val isConnected = connectionState is ControllerConnection.ConnectionState.Connected

        if (isConnected) {
          // Camera Switching & Rotation controls (Row 1)
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            // Lens Switcher Button
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(GlassBg)
                .border(1.dp, GlassBorder, RoundedCornerShape(8.dp))
                .clickable(enabled = availableCameras.size > 1) {
                  val nextIndex = (activeCameraIndex + 1) % availableCameras.size
                  controllerConnection.sendCommand("SET_CAMERA:$nextIndex")
                }
                .padding(horizontal = 8.dp, vertical = 8.dp),
              horizontalArrangement = Arrangement.Center
            ) {
              Icon(
                imageVector = Icons.Default.SwitchCamera,
                contentDescription = "Switch Lens",
                tint = if (availableCameras.size > 1) NeonCyan else TextSecondary,
                modifier = Modifier.size(16.dp)
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = availableCameras.getOrNull(activeCameraIndex)?.substringBefore(" ") ?: "Main Lens",
                color = if (availableCameras.size > 1) TextPrimary else TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
              )
            }

            // Viewfinder Rotation Toggle (Portrait Mode/Inversion Switch)
            val cameraRotationFlowVal = controllerConnection.cameraRotation.collectAsState().value
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(GlassBg)
                .border(1.dp, GlassBorder, RoundedCornerShape(8.dp))
                .clickable {
                  val nextRot = (cameraRotationFlowVal + 90) % 360
                  controllerConnection.setRotation(nextRot)
                }
                .padding(horizontal = 8.dp, vertical = 8.dp),
              horizontalArrangement = Arrangement.Center
            ) {
              Icon(
                imageVector = Icons.Default.CropRotate,
                contentDescription = "Rotate Viewfinder",
                tint = NeonCyan,
                modifier = Modifier.size(16.dp)
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "${cameraRotationFlowVal}°",
                color = TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
              )
            }
          }

          // Torch & Gallery buttons (Row 2)
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            // Torch/Flash Toggle Button
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isTorchOn) NeonCyan.copy(alpha = 0.2f) else GlassBg)
                .border(1.dp, if (isTorchOn) NeonCyan else GlassBorder, RoundedCornerShape(8.dp))
                .clickable {
                  controllerConnection.sendCommand("TOGGLE_TORCH")
                }
                .padding(horizontal = 8.dp, vertical = 8.dp),
              horizontalArrangement = Arrangement.Center
            ) {
              Icon(
                imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                contentDescription = "Toggle Flash",
                tint = if (isTorchOn) NeonCyan else TextSecondary,
                modifier = Modifier.size(16.dp)
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = if (isTorchOn) "Flash ON" else "Flash OFF",
                color = TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
              )
            }

            // Gallery Navigation Button
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(GlassBg)
                .border(1.dp, GlassBorder, RoundedCornerShape(8.dp))
                .clickable {
                  controllerConnection.sendCommand("PAUSE_STREAM")
                  onGalleryClick()
                }
                .padding(horizontal = 8.dp, vertical = 8.dp),
              horizontalArrangement = Arrangement.Center
            ) {
              Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = "Gallery",
                tint = NeonPurple,
                modifier = Modifier.size(16.dp)
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "Gallery",
                color = TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
              )
            }
          }

          val (minZoom, maxZoom) = zoomLimits
          if (maxZoom > minZoom) {
            Column(modifier = Modifier.fillMaxWidth()) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(
                  text = "Zoom: ${String.format("%.1fx", currentZoom)}",
                  color = TextPrimary,
                  fontSize = 13.sp,
                  fontWeight = FontWeight.Bold
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  listOf(0.5f, 1.0f, 2.0f, 5.0f).forEach { preset ->
                    if (preset in minZoom..maxZoom) {
                      Box(
                        modifier = Modifier
                          .clip(RoundedCornerShape(8.dp))
                          .background(if (currentZoom == preset) NeonCyan else GlassBg)
                          .border(1.dp, if (currentZoom == preset) NeonCyan else GlassBorder, RoundedCornerShape(8.dp))
                          .clickable { controllerConnection.sendCommand("SET_ZOOM:$preset") }
                          .padding(horizontal = 8.dp, vertical = 4.dp)
                      ) {
                        Text(
                          text = "${preset}x",
                          color = if (currentZoom == preset) Color.Black else TextPrimary,
                          fontSize = 11.sp,
                          fontWeight = FontWeight.Bold
                        )
                      }
                    }
                  }
                }
              }

              Spacer(modifier = Modifier.height(4.dp))

              Slider(
                value = currentZoom,
                onValueChange = { valZoom ->
                  controllerConnection.sendCommand("SET_ZOOM:$valZoom")
                },
                valueRange = minZoom..maxZoom,
                colors = SliderDefaults.colors(
                  activeTrackColor = NeonCyan,
                  thumbColor = NeonCyan,
                  inactiveTrackColor = GlassBorder
                ),
                modifier = Modifier.fillMaxWidth()
              )
            }
          }
        }

        // Button 1: Photo Shutter
        Button(
          onClick = { controllerConnection.sendCommand("TAKE_PHOTO") },
          enabled = isConnected && !isRecording,
          colors = ButtonDefaults.buttonColors(
            containerColor = NeonCyan,
            disabledContainerColor = GlassBorder
          ),
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
        ) {
          Icon(
            imageVector = Icons.Default.Camera,
            contentDescription = "Shutter Button",
            tint = if (isConnected && !isRecording) Color(0xFF0F0F15) else TextSecondary
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "Capture Photo",
            color = if (isConnected && !isRecording) Color(0xFF0F0F15) else TextSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          // Button 2: Start Record
          Button(
            onClick = { controllerConnection.sendCommand("START_RECORDING") },
            enabled = isConnected && !isRecording,
            colors = ButtonDefaults.buttonColors(
              containerColor = NeonPurple,
              disabledContainerColor = GlassBorder
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
              .weight(1f)
              .height(52.dp)
          ) {
            Icon(
              imageVector = Icons.Default.FiberManualRecord,
              contentDescription = "Record Button",
              tint = if (isConnected && !isRecording) Color.White else TextSecondary,
              modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = "Record",
              color = if (isConnected && !isRecording) Color.White else TextSecondary,
              fontWeight = FontWeight.Bold,
              fontSize = 14.sp
            )
          }

          // Button 3: Stop Record
          Button(
            onClick = { controllerConnection.sendCommand("STOP_RECORDING") },
            enabled = isConnected && isRecording,
            colors = ButtonDefaults.buttonColors(
              containerColor = Color.Red,
              disabledContainerColor = GlassBorder
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
              .weight(1f)
              .height(52.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Stop,
              contentDescription = "Stop Button",
              tint = if (isConnected && isRecording) Color.White else TextSecondary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = "Stop",
              color = if (isConnected && isRecording) Color.White else TextSecondary,
              fontWeight = FontWeight.Bold,
              fontSize = 14.sp
            )
          }
        }
      }
    }
  }

  if (showInstructions) {
    InstructionsDialog(
      title = "Controller Operator Guide",
      instructions = listOf(
        "Use 'Capture Photo' or 'Record' to trigger the remote camera shutter.",
        "Toggle the lens switcher to cycle between available cameras on the server.",
        "Adjust remote zoom using the slider or preset zoom factor options.",
        "Open the 'Gallery' to browse and download saved files directly over the offline link."
      ),
      prefKey = "never_show_controller_instructions",
      onDismiss = { showInstructions = false }
    )
  }
}
