package com.example.remotecamera.ui.controller

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwitchCamera
import androidx.compose.material.icons.filled.Wifi
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.remotecamera.net.ControllerConnection
import com.example.remotecamera.net.NsdHelper
import com.example.remotecamera.ui.components.InstructionsDialog
import com.example.remotecamera.ui.main.DarkBg
import com.example.remotecamera.ui.main.GlassBg
import com.example.remotecamera.ui.main.GlassBorder
import com.example.remotecamera.ui.main.NeonCyan
import com.example.remotecamera.ui.main.NeonPurple
import com.example.remotecamera.ui.main.TextPrimary
import com.example.remotecamera.ui.main.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class CaptureMode { PHOTO, VIDEO }

private data class DiscoveredServer(val name: String, val ip: String, val port: Int)

@Composable
fun ControllerModeScreen(
  controllerConnection: ControllerConnection,
  onGalleryClick: () -> Unit,
  onBackClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val coroutineScope = rememberCoroutineScope()
  val shutterFlashAlpha = remember { Animatable(0f) }

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
  val zoomPresets by controllerConnection.zoomPresets.collectAsState()
  val cameraRotation by controllerConnection.cameraRotation.collectAsState()

  var showInstructions by remember { mutableStateOf(false) }
  var captureMode by remember { mutableStateOf(CaptureMode.PHOTO) }

  // Trigger instructions dialog when successfully connected
  LaunchedEffect(connectionState) {
    if (connectionState is ControllerConnection.ConnectionState.Connected) {
      showInstructions = true
    }
  }

  // 2. Discover available Camera Servers via mDNS and let the user pick one,
  // rather than auto-connecting to whichever server answers first — with more
  // than one server/controller active on the network, that could pair the wrong pair.
  var discoveryMessage by remember { mutableStateOf("Scanning for Camera Servers on Wi-Fi...") }
  val discoveredServers = remember { mutableStateListOf<DiscoveredServer>() }
  var selectedServer by remember { mutableStateOf<DiscoveredServer?>(null) }
  val nsdHelper = remember { NsdHelper(context) }

  DisposableEffect(Unit) {
    nsdHelper.onDiscoveryStarted = {
      discoveryMessage = "Scanning for Camera Servers on Wi-Fi..."
    }
    nsdHelper.onServiceResolved = { name, ip, port ->
      if (discoveredServers.none { it.ip == ip && it.port == port }) {
        discoveredServers.add(DiscoveredServer(name, ip, port))
      }
    }
    nsdHelper.onServiceLost = { name ->
      discoveredServers.removeAll { it.name == name }
    }
    nsdHelper.onError = { error ->
      discoveryMessage = "mDNS discovery error: $error"
    }

    onDispose {
      nsdHelper.stopDiscovery()
    }
  }

  // Pulsing animation for the recording dot / shutter
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

  // Confirm recording actually starting/stopping on the server with a haptic tick,
  // since that can lag slightly behind the tap that triggered it. Skip the very
  // first composition so simply opening/connecting the screen doesn't buzz.
  val isFirstRecordingCheck = remember { mutableStateOf(true) }
  LaunchedEffect(isRecording) {
    // Keep the local mode toggle truthful to the server's actual recording state —
    // otherwise reconnecting (or a slow round-trip right after tapping the shutter)
    // can leave captureMode stuck on PHOTO while the server is really recording,
    // causing the next shutter tap to send TAKE_PHOTO instead of STOP_RECORDING.
    if (isRecording) {
      captureMode = CaptureMode.VIDEO
    }
    if (isFirstRecordingCheck.value) {
      isFirstRecordingCheck.value = false
    } else {
      haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
  }

  // A genuinely transient toast: shows briefly on a new status, then clears itself,
  // instead of persisting indefinitely until some other status happens to arrive.
  var transientStatus by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(cameraStatus) {
    val text = when {
      isRecording -> null // already shown via the REC badge, no need for a duplicate chip
      cameraStatus.startsWith("RECORD_FINISHED") -> "Recording Saved"
      cameraStatus.startsWith("PHOTO_SUCCESS") -> "Photo Saved!"
      cameraStatus.startsWith("PHOTO_ERROR") -> "Photo Capture Failed"
      cameraStatus.startsWith("RECORD_ERROR") -> "Recording Failed"
      else -> null
    }
    transientStatus = text
    if (text != null) {
      delay(2500)
      transientStatus = null
    }
  }

  val isConnected = connectionState is ControllerConnection.ConnectionState.Connected
  val (minZoom, maxZoom) = zoomLimits

  // Only scan while not connected: stop once paired, and resume automatically
  // if the connection later drops so the picker repopulates for a fresh choice.
  LaunchedEffect(isConnected) {
    if (isConnected) {
      nsdHelper.stopDiscovery()
    } else {
      discoveredServers.clear()
      nsdHelper.discoverServices()
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(DarkBg)
  ) {
    // Background layer: live feed fit to the screen at its true aspect ratio
    // (letterboxed, not cropped/stretched), or a centered placeholder while scanning/connecting
    if (isConnected && viewfinderBitmap != null) {
      val bitmap = viewfinderBitmap!!
      BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bitmapAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        // Prefer filling the width (letterboxed top/bottom) as usual, but if that
        // would make the image taller than the viewport — e.g. a rotated frame whose
        // width/height swapped into a very tall ratio — fit by height instead
        // (pillarboxed) so the feed never overflows off-screen.
        val fitsByWidth = maxWidth / bitmapAspect <= maxHeight
        Image(
          bitmap = bitmap.asImageBitmap(),
          contentDescription = "Viewfinder Stream",
          contentScale = ContentScale.Fit,
          modifier = if (fitsByWidth) {
            Modifier.fillMaxWidth().aspectRatio(bitmapAspect)
          } else {
            Modifier.fillMaxHeight().aspectRatio(bitmapAspect)
          }
        )
      }
    } else if (connectionState is ControllerConnection.ConnectionState.Connecting) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Brush.verticalGradient(listOf(DarkBg, Color(0xFF1A1A24)))),
        contentAlignment = Alignment.Center
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
          modifier = Modifier.padding(32.dp)
        ) {
          CircularProgressIndicator(
            color = NeonCyan,
            strokeWidth = 3.dp,
            modifier = Modifier.size(48.dp)
          )
          Spacer(modifier = Modifier.height(16.dp))
          Text(
            text = "Connecting to ${selectedServer?.name ?: "device"}...",
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
          )
          Spacer(modifier = Modifier.height(16.dp))
          Text(
            text = "Cancel",
            color = NeonCyan,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable {
              controllerConnection.disconnect()
              selectedServer = null
            }
          )
        }
      }
    } else {
      // Device picker: pick which discovered Camera Server to pair with, instead
      // of auto-connecting to whichever one answers first.
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Brush.verticalGradient(listOf(DarkBg, Color(0xFF1A1A24))))
      ) {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(top = 100.dp, start = 20.dp, end = 20.dp, bottom = 24.dp)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(text = "Select a Camera Server", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = {
              discoveredServers.clear()
              nsdHelper.discoverServices()
            }) {
              Icon(imageVector = Icons.Default.Refresh, contentDescription = "Rescan", tint = NeonCyan)
            }
          }
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = if (connectionState is ControllerConnection.ConnectionState.Error) {
              "Couldn't connect to ${selectedServer?.name ?: "that device"}: ${(connectionState as ControllerConnection.ConnectionState.Error).message}"
            } else {
              discoveryMessage
            },
            color = if (connectionState is ControllerConnection.ConnectionState.Error) Color.Red else TextSecondary,
            fontSize = 12.sp,
            lineHeight = 16.sp
          )
          Spacer(modifier = Modifier.height(20.dp))

          if (discoveredServers.isEmpty()) {
            Column(
              modifier = Modifier.fillMaxWidth().weight(1f),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.Center
            ) {
              Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = "Scanning",
                tint = NeonPurple,
                modifier = Modifier.size(40.dp).alpha(alphaPulse)
              )
              Spacer(modifier = Modifier.height(16.dp))
              Text(
                text = "No camera servers found yet.\nOpen RemoteCamera in Camera Server mode on the other device.",
                color = TextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp
              )
            }
          } else {
            LazyColumn(
              modifier = Modifier.weight(1f),
              verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              items(discoveredServers, key = { "${it.ip}:${it.port}" }) { server ->
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(GlassBg)
                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                    .clickable {
                      selectedServer = server
                      controllerConnection.connect(server.ip, server.port)
                    }
                    .padding(16.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Icon(imageVector = Icons.Default.Wifi, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(22.dp))
                  Spacer(modifier = Modifier.width(12.dp))
                  Column(modifier = Modifier.weight(1f)) {
                    Text(text = server.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(text = "${server.ip}:${server.port}", color = TextSecondary, fontSize = 11.sp)
                  }
                }
              }
            }
          }
        }
      }
    }

    // Top scrim for toolbar legibility over the feed. Remembered since this composable
    // recomposes on every new viewfinder frame (~150-300ms) and these gradients never change.
    val topScrimBrush = remember { Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)) }
    val bottomScrimBrush = remember { Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))) }
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(140.dp)
        .align(Alignment.TopStart)
        .background(topScrimBrush)
    )

    // Bottom scrim for control legibility over the feed
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight(0.42f)
        .align(Alignment.BottomStart)
        .background(bottomScrimBrush)
    )

    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 16.dp)
    ) {
      // Toolbar
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
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

        if (isConnected) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            TelemetryChip(
              icon = Icons.Default.Storage,
              text = diskSpace.replace(" free", ""),
              visible = diskSpace.isNotEmpty()
            )
            TelemetryChip(
              icon = if (isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryStd,
              text = "$batteryLevel%",
              tint = if (batteryLevel < 20) Color.Red else Color(0xFF00C853)
            )
          }
        }
      }

      // REC indicator + transient status chip, just under the toolbar
      if (isConnected) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          if (isRecording) {
            Row(
              modifier = Modifier
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
              Text(text = "REC", color = Color.Red, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
          } else {
            Spacer(modifier = Modifier.width(1.dp))
          }

          AnimatedVisibility(visible = transientStatus != null, enter = fadeIn(), exit = fadeOut()) {
            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
              Text(text = transientStatus ?: "", color = NeonCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
          }
        }
      }

      Spacer(modifier = Modifier.weight(1f))

      // Bottom controls, floating over the feed
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        if (isConnected) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
          ) {
            OverlayIconButton(
              icon = Icons.Default.CropRotate,
              contentDescription = "Rotate Viewfinder",
              tint = NeonCyan,
              onClick = { controllerConnection.setRotation((cameraRotation + 90) % 360) }
            )
            Spacer(modifier = Modifier.width(8.dp))
            OverlayIconButton(
              icon = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
              contentDescription = "Toggle Flash",
              tint = if (isTorchOn) NeonCyan else TextSecondary,
              onClick = { controllerConnection.sendCommand("TOGGLE_TORCH") }
            )
          }
          if (maxZoom > minZoom) {
            Column(modifier = Modifier.fillMaxWidth()) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(
                  text = "${String.format("%.1fx", currentZoom)}",
                  color = TextPrimary,
                  fontSize = 13.sp,
                  fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  zoomPresets.forEach { preset ->
                    if (preset in minZoom..maxZoom) {
                      // Hardware-reported zoom ratios rarely land bit-exact on a
                      // requested preset value, so compare with a small tolerance
                      // instead of exact Float equality.
                      val isSelected = kotlin.math.abs(currentZoom - preset) < 0.05f
                      Box(
                        modifier = Modifier
                          .clip(RoundedCornerShape(8.dp))
                          .background(if (isSelected) NeonCyan else Color.Black.copy(alpha = 0.4f))
                          .border(1.dp, if (isSelected) NeonCyan else GlassBorder, RoundedCornerShape(8.dp))
                          .clickable { controllerConnection.sendCommand("SET_ZOOM:$preset") }
                          .padding(horizontal = 8.dp, vertical = 4.dp)
                      ) {
                        Text(
                          text = "${preset}x",
                          color = if (isSelected) Color.Black else TextPrimary,
                          fontSize = 11.sp,
                          fontWeight = FontWeight.Bold
                        )
                      }
                    }
                  }
                }
              }

              Slider(
                value = currentZoom,
                onValueChange = { valZoom -> controllerConnection.sendCommand("SET_ZOOM:$valZoom") },
                valueRange = minZoom..maxZoom,
                colors = SliderDefaults.colors(
                  activeTrackColor = NeonCyan,
                  thumbColor = NeonCyan,
                  inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                ),
                modifier = Modifier.fillMaxWidth()
              )
            }
          }

          ModeSwitch(
            mode = captureMode,
            enabled = !isRecording,
            onModeChange = { captureMode = it },
            modifier = Modifier.align(Alignment.CenterHorizontally)
          )
        }

        // Shutter row: gallery entry / shutter / lens switch
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Box(modifier = Modifier.width(64.dp), contentAlignment = Alignment.Center) {
            Box(
              modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(GlassBg)
                .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
                .clickable(enabled = isConnected) {
                  controllerConnection.sendCommand("PAUSE_STREAM")
                  onGalleryClick()
                },
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = "Gallery",
                tint = if (isConnected) TextPrimary else TextSecondary,
                modifier = Modifier.size(22.dp)
              )
            }
          }

          Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            ShutterButton(
              mode = captureMode,
              isRecording = isRecording,
              enabled = isConnected,
              pulseAlpha = alphaPulse,
              onClick = {
                // isRecording (the server's confirmed state) always takes priority over
                // the locally-selected mode: a recording in progress must always be
                // stoppable, and must never be able to trigger TAKE_PHOTO underneath it,
                // even if captureMode hasn't caught up yet (e.g. right after tapping
                // "start" but before the server's confirmation round-trips back).
                when {
                  isRecording -> controllerConnection.sendCommand("STOP_RECORDING")
                  captureMode == CaptureMode.PHOTO -> {
                    controllerConnection.sendCommand("TAKE_PHOTO")
                    coroutineScope.launch {
                      shutterFlashAlpha.snapTo(0.85f)
                      shutterFlashAlpha.animateTo(0f, tween(220))
                    }
                  }
                  captureMode == CaptureMode.VIDEO -> controllerConnection.sendCommand("START_RECORDING")
                }
              }
            )
          }

          Box(modifier = Modifier.width(64.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              val canSwitchLens = isConnected && availableCameras.size > 1
              Box(
                modifier = Modifier
                  .size(48.dp)
                  .clip(RoundedCornerShape(14.dp))
                  .background(GlassBg)
                  .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
                  .clickable(enabled = canSwitchLens) {
                    val nextIndex = (activeCameraIndex + 1) % availableCameras.size
                    controllerConnection.sendCommand("SET_CAMERA:$nextIndex")
                  },
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  imageVector = Icons.Default.SwitchCamera,
                  contentDescription = "Switch Lens",
                  tint = if (canSwitchLens) TextPrimary else TextSecondary,
                  modifier = Modifier.size(22.dp)
                )
              }
              if (isConnected) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                  text = availableCameras.getOrNull(activeCameraIndex)?.substringBefore(" ") ?: "Main",
                  color = TextSecondary,
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Bold
                )
              }
            }
          }
        }
      }
    }

    // Shutter flash: a brief white flash simulating the physical shutter firing,
    // shown immediately on tap rather than waiting on the server's PHOTO_SUCCESS reply.
    if (shutterFlashAlpha.value > 0f) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.White.copy(alpha = shutterFlashAlpha.value))
      )
    }
  }

  if (showInstructions) {
    InstructionsDialog(
      title = "Controller Operator Guide",
      instructions = listOf(
        "Tap the shutter button to capture a photo, or switch to Video mode to start/stop recording.",
        "Toggle the lens switcher to cycle between available cameras on the server.",
        "Adjust remote zoom using the slider or preset zoom factor options.",
        "Open the gallery icon to browse and download saved files directly over the offline link."
      ),
      prefKey = "never_show_controller_instructions",
      onDismiss = { showInstructions = false }
    )
  }
}

@Composable
private fun OverlayIconButton(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  contentDescription: String,
  tint: Color,
  onClick: () -> Unit
) {
  Box(
    modifier = Modifier
      .size(44.dp)
      .clip(CircleShape)
      .background(GlassBg)
      .border(1.dp, GlassBorder, CircleShape)
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center
  ) {
    Icon(imageVector = icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
  }
}

@Composable
private fun TelemetryChip(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  text: String,
  tint: Color = NeonCyan,
  visible: Boolean = true
) {
  if (!visible) return
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .clip(RoundedCornerShape(8.dp))
      .background(GlassBg)
      .border(1.dp, GlassBorder, RoundedCornerShape(8.dp))
      .padding(horizontal = 8.dp, vertical = 6.dp)
  ) {
    Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
    Spacer(modifier = Modifier.width(4.dp))
    Text(text = text, color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
  }
}

@Composable
private fun ModeSwitch(
  mode: CaptureMode,
  enabled: Boolean,
  onModeChange: (CaptureMode) -> Unit,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier
      .clip(RoundedCornerShape(50))
      .background(GlassBg)
      .border(1.dp, GlassBorder, RoundedCornerShape(50))
      .alpha(if (enabled) 1f else 0.5f)
      .padding(4.dp)
  ) {
    listOf(CaptureMode.PHOTO to "PHOTO", CaptureMode.VIDEO to "VIDEO").forEach { (segment, label) ->
      val selected = segment == mode
      Box(
        modifier = Modifier
          .clip(RoundedCornerShape(50))
          .background(if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent)
          .clickable(enabled = enabled) { onModeChange(segment) }
          .padding(horizontal = 18.dp, vertical = 6.dp)
      ) {
        Text(
          text = label,
          color = if (selected) (if (segment == CaptureMode.VIDEO) Color.Red else NeonCyan) else TextSecondary,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold
        )
      }
    }
  }
}

@Composable
private fun ShutterButton(
  mode: CaptureMode,
  isRecording: Boolean,
  enabled: Boolean,
  pulseAlpha: Float,
  onClick: () -> Unit
) {
  val haptic = LocalHapticFeedback.current
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()
  val pressScale by animateFloatAsState(
    targetValue = if (isPressed) 0.88f else 1f,
    animationSpec = spring(stiffness = Spring.StiffnessMedium),
    label = "shutterPressScale"
  )
  val ringColor = if (mode == CaptureMode.VIDEO) Color.Red else NeonCyan
  Box(
    modifier = Modifier
      .size(78.dp)
      .scale(pressScale)
      .clip(CircleShape)
      .background(Color.Black.copy(alpha = 0.3f))
      .border(4.dp, if (enabled) ringColor else TextSecondary, CircleShape)
      .clickable(
        enabled = enabled,
        interactionSource = interactionSource,
        indication = null
      ) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onClick()
      },
    contentAlignment = Alignment.Center
  ) {
    if (isRecording) {
      Box(
        modifier = Modifier
          .size(28.dp)
          .alpha(pulseAlpha)
          .clip(RoundedCornerShape(6.dp))
          .background(Color.Red)
      )
    } else {
      Box(
        modifier = Modifier
          .size(60.dp)
          .clip(CircleShape)
          .background(if (mode == CaptureMode.VIDEO) Color.Red else Color.White)
      )
    }
  }
}
