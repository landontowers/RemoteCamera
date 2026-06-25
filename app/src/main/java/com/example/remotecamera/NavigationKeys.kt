package com.example.remotecamera

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Main : NavKey
@Serializable data object CameraMode : NavKey
@Serializable data object ControllerMode : NavKey
@Serializable data object GalleryMode : NavKey
