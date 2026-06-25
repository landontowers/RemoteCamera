package com.example.remotecamera.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation3.runtime.NavKey
import com.example.remotecamera.CameraMode
import com.example.remotecamera.ControllerMode

// Sleek Color Palette
val DarkBg = Color(0xFF0F0F15)
val GlassBg = Color(0x15FFFFFF)
val GlassBorder = Color(0x25FFFFFF)
val NeonCyan = Color(0xFF00E5FF)
val NeonPurple = Color(0xFFD500F9)
val TextPrimary = Color(0xFFF5F5FA)
val TextSecondary = Color(0xFF9E9EAE)

@Composable
fun MainScreen(
  onItemClick: (NavKey) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val permissions = remember {
    val list = mutableListOf(
      Manifest.permission.CAMERA,
      Manifest.permission.RECORD_AUDIO
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      list.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    list.toTypedArray()
  }

  var permissionsGranted by remember {
    mutableStateOf(
      permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
      }
    )
  }

  val permissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestMultiplePermissions()
  ) { results ->
    permissionsGranted = results.values.all { it }
  }

  LaunchedEffect(Unit) {
    if (!permissionsGranted) {
      permissionLauncher.launch(permissions)
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(DarkBg)
  ) {
    if (!permissionsGranted) {
      PermissionRequiredScreen(
        onRequestPermissions = { permissionLauncher.launch(permissions) }
      )
    } else {
      DashboardScreen(onItemClick = onItemClick)
    }
  }
}

@Composable
fun PermissionRequiredScreen(
  onRequestPermissions: () -> Unit,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(32.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Icon(
      imageVector = Icons.Default.CameraAlt,
      contentDescription = "Camera Required",
      tint = NeonCyan,
      modifier = Modifier.size(72.dp)
    )
    Spacer(modifier = Modifier.height(24.dp))
    Text(
      text = "Permissions Required",
      color = TextPrimary,
      fontSize = 24.sp,
      fontWeight = FontWeight.Bold,
      fontFamily = FontFamily.SansSerif
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
      text = "This app requires access to the camera and audio recording to capture and stream media offline.",
      color = TextSecondary,
      fontSize = 14.sp,
      textAlign = TextAlign.Center,
      lineHeight = 20.sp
    )
    Spacer(modifier = Modifier.height(32.dp))
    Button(
      onClick = onRequestPermissions,
      colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier.fillMaxWidth().height(50.dp)
    ) {
      Text(
        text = "Grant Permissions",
        color = Color(0xFF0F0F15),
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp
      )
    }
  }
}

@Composable
fun DashboardScreen(
  onItemClick: (NavKey) -> Unit,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(24.dp),
    verticalArrangement = Arrangement.SpaceBetween
  ) {
    // Header
    Column(modifier = Modifier.padding(top = 40.dp)) {
      Text(
        text = "Remote Camera",
        color = TextPrimary,
        fontSize = 32.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = (-0.5).sp
      )
      Text(
        text = "Privacy-First Peer-to-Peer Streaming",
        color = NeonCyan,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp
      )
    }

    // Role Selection
    Column(
      verticalArrangement = Arrangement.spacedBy(20.dp),
      modifier = Modifier.fillMaxWidth()
    ) {
      Text(
        text = "SELECT DEVICE MODE",
        color = TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp
      )

      RoleCard(
        title = "Camera Server",
        description = "Mount remotely. Ephemeral streaming server and high-resolution local recorder.",
        icon = Icons.Default.CameraAlt,
        accentColor = NeonCyan,
        onClick = { onItemClick(CameraMode) }
      )

      RoleCard(
        title = "Controller / Client",
        description = "Hold in hand. view live stream viewfinder, trigger capture and toggle record offline.",
        icon = Icons.Default.VideogameAsset,
        accentColor = NeonPurple,
        onClick = { onItemClick(ControllerMode) }
      )
    }

    // Footer info
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(GlassBg)
        .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
        .padding(16.dp)
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
      ) {
        Box(
          modifier = Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF00C853))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "100% Offline Network - No Cloud Server Required",
          color = TextSecondary,
          fontSize = 12.sp,
          fontWeight = FontWeight.Normal
        )
      }
    }
  }
}

@Composable
fun RoleCard(
  title: String,
  description: String,
  icon: ImageVector,
  accentColor: Color,
  onClick: () -> Unit
) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .height(130.dp)
      .clip(RoundedCornerShape(20.dp))
      .border(1.dp, GlassBorder, RoundedCornerShape(20.dp))
      .clickable(onClick = onClick),
    colors = CardDefaults.cardColors(containerColor = GlassBg)
  ) {
    Row(
      modifier = Modifier
        .fillMaxSize()
        .padding(20.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          color = TextPrimary,
          fontSize = 20.sp,
          fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = description,
          color = TextSecondary,
          fontSize = 12.sp,
          lineHeight = 16.sp
        )
      }
      Spacer(modifier = Modifier.width(16.dp))
      Box(
        modifier = Modifier
          .size(52.dp)
          .clip(RoundedCornerShape(16.dp))
          .background(Brush.radialGradient(listOf(accentColor.copy(alpha = 0.3f), Color.Transparent)))
          .border(1.5.dp, accentColor, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = icon,
          contentDescription = title,
          tint = accentColor,
          modifier = Modifier.size(24.dp)
        )
      }
    }
  }
}
