package com.example.remotecamera.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.remotecamera.net.ControllerConnection
import com.example.remotecamera.net.GalleryFile
import com.example.remotecamera.ui.components.InstructionsDialog
import com.example.remotecamera.ui.main.DarkBg
import com.example.remotecamera.ui.main.GlassBg
import com.example.remotecamera.ui.main.GlassBorder
import com.example.remotecamera.ui.main.NeonCyan
import com.example.remotecamera.ui.main.NeonPurple
import com.example.remotecamera.ui.main.TextPrimary
import com.example.remotecamera.ui.main.TextSecondary

@Composable
fun GalleryScreen(
    controllerConnection: ControllerConnection,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val files by controllerConnection.galleryFiles.collectAsState()
    val isDownloading by controllerConnection.isDownloading.collectAsState()
    val downloadProgress by controllerConnection.downloadProgress.collectAsState()
    val downloadError by controllerConnection.downloadError.collectAsState()

    var downloadingFileName by remember { mutableStateOf<String?>(null) }
    val downloadedFiles = remember { mutableStateListOf<String>() }
    var showInstructions by remember { mutableStateOf(true) }

    // Tab state: 0 = All, 1 = Photos, 2 = Videos
    var selectedTab by remember { mutableStateOf(0) }

    val handleBack = {
        controllerConnection.sendCommand("RESUME_STREAM")
        onBackClick()
    }

    // Intercept back actions
    BackHandler {
        handleBack()
    }

    LaunchedEffect(Unit) {
        controllerConnection.sendCommand("GET_GALLERY")
    }

    LaunchedEffect(isDownloading) {
        if (!isDownloading && downloadingFileName != null) {
            if (downloadError != null) {
                android.widget.Toast.makeText(context, "Download failed: $downloadError", android.widget.Toast.LENGTH_LONG).show()
            } else {
                android.widget.Toast.makeText(context, "Downloaded $downloadingFileName to Downloads!", android.widget.Toast.LENGTH_SHORT).show()
                downloadedFiles.add(downloadingFileName!!)
            }
            downloadingFileName = null
        }
    }

    // Filter files
    val filteredFiles = remember(files, selectedTab) {
        when (selectedTab) {
            1 -> files.filter { it.mime.startsWith("image/") }
            2 -> files.filter { it.mime.startsWith("video/") }
            else -> files
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(16.dp)
    ) {
        // Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = handleBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Lens Gallery",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Camera Server DCIM",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            IconButton(
                onClick = { controllerConnection.sendCommand("GET_GALLERY") },
                enabled = !isDownloading
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = if (isDownloading) TextSecondary else NeonCyan
                )
            }
        }

        // Tabs
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            contentColor = NeonCyan,
            divider = {},
            indicator = { tabPositions ->
                if (selectedTab < tabPositions.size) {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = NeonCyan
                    )
                }
            },
            modifier = Modifier.padding(vertical = 12.dp)
        ) {
            val tabs = listOf("All Media", "Photos", "Videos")
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 14.sp
                        )
                    },
                    selectedContentColor = NeonCyan,
                    unselectedContentColor = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Files List
        if (filteredFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = "No Media",
                        tint = GlassBorder,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No offline media found",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(filteredFiles) { file ->
                    val isCurrentDownloading = downloadingFileName == file.name && isDownloading
                    val isCurrentDownloaded = downloadedFiles.contains(file.name)
                    val isVideo = file.mime.startsWith("video/")

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(BorderStroke(1.dp, GlassBorder), RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = GlassBg)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Thumbnail/Icon
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (isVideo) NeonPurple.copy(alpha = 0.15f) else NeonCyan.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (isVideo) Icons.Default.Videocam else Icons.Default.Image,
                                        contentDescription = "Media Type",
                                        tint = if (isVideo) NeonPurple else NeonCyan,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                // Metadata
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = file.name,
                                        color = TextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = formatFileSize(file.size),
                                            color = TextSecondary,
                                            fontSize = 11.sp
                                        )
                                        Text(
                                            text = "•",
                                            color = GlassBorder,
                                            fontSize = 11.sp
                                        )
                                        Text(
                                            text = file.mime.substringAfter("/").uppercase(),
                                            color = TextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            // Action Area
                            Box(
                                modifier = Modifier.size(40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                when {
                                    isCurrentDownloading -> {
                                        CircularProgressIndicator(
                                            progress = { downloadProgress },
                                            color = NeonCyan,
                                            strokeWidth = 3.dp,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                    isCurrentDownloaded -> {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Downloaded",
                                            tint = Color(0xFF00C853),
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                    else -> {
                                        IconButton(
                                            onClick = {
                                                if (!isDownloading) {
                                                    downloadingFileName = file.name
                                                    controllerConnection.sendCommand("DOWNLOAD_FILE:${file.name}")
                                                }
                                            },
                                            enabled = !isDownloading
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Download,
                                                contentDescription = "Download File",
                                                tint = if (isDownloading) TextSecondary else TextPrimary
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

    if (showInstructions) {
        InstructionsDialog(
            title = "Wireless Gallery Guide",
            instructions = listOf(
                "Viewfinder streaming is paused to optimize file download transfer rates.",
                "Browse through all photos and videos captured on the remote camera server.",
                "Tap the Download icon to save media files directly to your device's local Downloads folder.",
                "Downloaded files display a green checkmark indicating successful local transfer."
            ),
            prefKey = "never_show_gallery_instructions",
            onDismiss = { showInstructions = false }
        )
    }
}

fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
