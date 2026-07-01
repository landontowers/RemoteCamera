plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.remotecamera"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.remotecamera"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

// Rename the generic app-<variant>.apk output to RemoteCamera.apk so it reflects
// the app rather than the module name. Done as a post-build rename (rather than via
// the Variant API's output-file property) since that API surface has been in churn
// across recent AGP versions.
androidComponents {
    onVariants { variant ->
        val variantNameCapitalized = variant.name.replaceFirstChar { it.uppercase() }
        // Resolve the directory to a plain File at configuration time so the task
        // action doesn't capture a live Project/layout reference (which the
        // configuration cache disallows serializing).
        val apkDir = layout.buildDirectory.dir("outputs/apk/${variant.name}").get().asFile
        val renameTask = tasks.register("renameApk${variantNameCapitalized}") {
            doLast {
                apkDir.listFiles { f -> f.extension == "apk" && f.name != "RemoteCamera.apk" }
                    ?.forEach { it.copyTo(File(apkDir, "RemoteCamera.apk"), overwrite = true); it.delete() }
            }
        }
        afterEvaluate {
            tasks.named("assemble$variantNameCapitalized") { finalizedBy(renameTask) }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation("androidx.compose.material:material-icons-extended")
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation("org.mockito:mockito-core:5.11.0")
  testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // CameraX
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.video)
  implementation(libs.androidx.camera.view)
}
