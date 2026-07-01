package com.example.remotecamera.ui.camera

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.remotecamera.service.CameraService
import com.example.remotecamera.ui.main.DarkBg
import com.example.remotecamera.ui.main.GlassBg
import com.example.remotecamera.ui.main.GlassBorder
import com.example.remotecamera.ui.main.NeonCyan
import com.example.remotecamera.ui.main.TextPrimary
import com.example.remotecamera.ui.main.TextSecondary

@Composable
fun CameraModeScreen(
  onBackClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  var showInstructions by remember { mutableStateOf(false) }

  // Observables from service
  val isRunning by CameraService.isRunning.collectAsState()
  val port by CameraService.serverPort.collectAsState()
  val clientConnected by CameraService.clientConnected.collectAsState()
  val clientIp by CameraService.clientIp.collectAsState()
  val isRecording by CameraService.isRecording.collectAsState()
  val sysMessage by CameraService.systemMessage.collectAsState()

  // Pulsing animation for status indicators
  val infiniteTransition = rememberInfiniteTransition(label = "pulse")
  val alphaPulse by infiniteTransition.animateFloat(
    initialValue = 0.3f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(1000, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "pulseAlpha"
  )

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(DarkBg)
      .padding(24.dp)
  ) {
    // Toolbar
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = 24.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      IconButton(onClick = onBackClick) {
        Icon(
          imageVector = Icons.Default.ArrowBack,
          contentDescription = "Back",
          tint = TextPrimary
        )
      }
      Spacer(modifier = Modifier.width(16.dp))
      Text(
        text = "Lens Server Mode",
        color = TextPrimary,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold
      )
    }

    Spacer(modifier = Modifier.height(32.dp))

    // Main status box
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
          .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
      ) {
        Box(
          modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(50))
            .background(if (isRunning) NeonCyan.copy(alpha = 0.2f) else Color.Red.copy(alpha = 0.1f))
            .border(2.dp, if (isRunning) NeonCyan else Color.Red.copy(alpha = 0.6f), RoundedCornerShape(50)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.PowerSettingsNew,
            contentDescription = "Server State",
            tint = if (isRunning) NeonCyan else Color.Red,
            modifier = Modifier.size(32.dp)
          )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
          text = if (isRunning) "SERVER ACTIVE" else "SERVER INACTIVE",
          color = TextPrimary,
          fontSize = 18.sp,
          fontWeight = FontWeight.Bold
        )
        Text(
          text = sysMessage ?: "No status reports",
          color = TextSecondary,
          fontSize = 13.sp,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(top = 4.dp)
        )
      }
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Server Info Table
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(20.dp))
        .border(1.dp, GlassBorder, RoundedCornerShape(20.dp)),
      colors = CardDefaults.cardColors(containerColor = GlassBg)
    ) {
      Column(modifier = Modifier.padding(20.dp)) {
        InfoRow(
          label = "Local IP Address",
          value = if (isRunning) getIPAddress(true) else "N/A"
        )
        Spacer(modifier = Modifier.height(12.dp))
        InfoRow(
          label = "Active Port",
          value = if (isRunning && port > 0) port.toString() else "N/A"
        )
        Spacer(modifier = Modifier.height(12.dp))
        InfoRow(
          label = "Client Status",
          value = if (clientConnected) "Connected" else "Waiting for Client",
          valueColor = if (clientConnected) Color(0xFF00C853) else TextSecondary,
          showPulse = !clientConnected && isRunning,
          pulseAlpha = alphaPulse
        )
        if (clientConnected && clientIp != null) {
          Spacer(modifier = Modifier.height(12.dp))
          InfoRow(
            label = "Client IP",
            value = clientIp!!
          )
        }
        Spacer(modifier = Modifier.height(12.dp))
        InfoRow(
          label = "Recording Status",
          value = if (isRecording) "RECORDING NOW" else "Idle",
          valueColor = if (isRecording) Color.Red else TextSecondary,
          showPulse = isRecording,
          pulseAlpha = alphaPulse
        )
      }
    }

    Spacer(modifier = Modifier.height(32.dp))

    // Start/Stop controls
    Button(
      onClick = {
        if (isRunning) {
          CameraService.stop(context)
        } else {
          CameraService.start(context)
          showInstructions = true
        }
      },
      colors = ButtonDefaults.buttonColors(
        containerColor = if (isRunning) Color.Red.copy(alpha = 0.8f) else NeonCyan
      ),
      shape = RoundedCornerShape(16.dp),
      modifier = Modifier
        .fillMaxWidth()
        .height(56.dp)
        .border(
          1.dp,
          if (isRunning) Color.Red else NeonCyan.copy(alpha = 0.5f),
          RoundedCornerShape(16.dp)
        )
    ) {
      Icon(
        imageVector = if (isRunning) Icons.Default.LinkOff else Icons.Default.Link,
        contentDescription = "Control Link",
        tint = if (isRunning) Color.White else Color(0xFF0F0F15)
      )
      Spacer(modifier = Modifier.width(10.dp))
      Text(
        text = if (isRunning) "Shutdown Lens Server" else "Launch Lens Server",
        color = if (isRunning) Color.White else Color(0xFF0F0F15),
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp
      )
    }

    Spacer(modifier = Modifier.weight(1f))

    // mDNS broadcast info footer
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 16.dp),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = "Broadcasting as \"PoleCameraRemote (${android.os.Build.MODEL})\" via mDNS",
        color = TextSecondary,
        fontSize = 11.sp
      )
    }

    if (showInstructions) {
      InstructionsDialog(
        title = "Server Configuration Guide",
        instructions = listOf(
          "Ensure both devices are on the same Wi-Fi network (or host Hotspot from one).",
          "Note the Local IP Address shown on this screen.",
          "Open the app on the Controller device and go to Controller Mode.",
          "Keep this screen open to allow continuous background streaming."
        ),
        prefKey = "never_show_server_instructions",
        onDismiss = { showInstructions = false }
      )
    }
  }
}

@Composable
fun InfoRow(
  label: String,
  value: String,
  valueColor: Color = TextPrimary,
  showPulse: Boolean = false,
  pulseAlpha: Float = 1f
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = label,
      color = TextSecondary,
      fontSize = 13.sp,
      fontWeight = FontWeight.Normal
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
      if (showPulse) {
        Box(
          modifier = Modifier
            .size(6.dp)
            .clip(RoundedCornerShape(50))
            .alpha(pulseAlpha)
            .background(if (valueColor == Color.Red) Color.Red else NeonCyan)
        )
        Spacer(modifier = Modifier.width(6.dp))
      }
      Text(
        text = value,
        color = valueColor,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold
      )
    }
  }
}

// Utility to get network IP address offline
fun getIPAddress(useIPv4: Boolean): String {
  try {
    val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
    while (interfaces.hasMoreElements()) {
      val intf = interfaces.nextElement()
      val addrs = intf.inetAddresses
      while (addrs.hasMoreElements()) {
        val addr = addrs.nextElement()
        if (!addr.isLoopbackAddress) {
          val sAddr = addr.hostAddress ?: continue
          val isIPv4 = sAddr.indexOf(':') < 0
          if (useIPv4) {
            if (isIPv4) return sAddr
          } else {
            if (!isIPv4) {
              val delim = sAddr.indexOf('%') // drop ip6 zone suffix
              return if (delim < 0) sAddr.uppercase() else sAddr.substring(0, delim).uppercase()
            }
          }
        }
      }
    }
  } catch (ex: java.lang.Exception) {
    ex.printStackTrace()
  }
  return "Disconnected"
}
