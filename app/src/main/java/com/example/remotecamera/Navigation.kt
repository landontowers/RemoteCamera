package com.example.remotecamera

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.remotecamera.ui.camera.CameraModeScreen
import com.example.remotecamera.ui.controller.ControllerModeScreen
import com.example.remotecamera.ui.gallery.GalleryScreen
import com.example.remotecamera.ui.main.MainScreen

@Composable
fun MainNavigation() {
  val context = androidx.compose.ui.platform.LocalContext.current
  val backStack = rememberNavBackStack(Main)
  val sharedControllerConnection = remember { com.example.remotecamera.net.ControllerConnection(context.applicationContext) }

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Main> {
          MainScreen(
            onItemClick = { navKey -> backStack.add(navKey) },
            modifier = Modifier.fillMaxSize()
          )
        }
        entry<CameraMode> {
          CameraModeScreen(
            onBackClick = { backStack.removeLastOrNull() },
            modifier = Modifier.fillMaxSize()
          )
        }
        entry<ControllerMode> {
          ControllerModeScreen(
            controllerConnection = sharedControllerConnection,
            onGalleryClick = { backStack.add(GalleryMode) },
            onBackClick = { backStack.removeLastOrNull() },
            modifier = Modifier.fillMaxSize()
          )
        }
        entry<GalleryMode> {
          GalleryScreen(
            controllerConnection = sharedControllerConnection,
            onBackClick = { backStack.removeLastOrNull() },
            modifier = Modifier.fillMaxSize()
          )
        }
      },
  )
}
