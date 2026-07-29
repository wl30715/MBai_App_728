plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

val productVersion = "2.0.5"
val productVersionCode = 205

android {
    namespace = "com.example.mbaiimageai"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.mbaiimageai"
        minSdk = 24
        targetSdk = 36
        versionCode = productVersionCode
        versionName = productVersion
    }

    flavorDimensions += "server"
    productFlavors {
        create("local") {
            dimension = "server"
            applicationIdSuffix = ".local"
            versionNameSuffix = "-local"
            buildConfigField("String", "APP_ORIGIN", "\"http://127.0.0.1:8787\"")
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            resValue("string", "app_name", "墨白AI 测试版")
        }
        create("production") {
            dimension = "server"
            buildConfigField("String", "APP_ORIGIN", "\"https://mbai.wang\"")
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            resValue("string", "app_name", "墨白AI")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      resValues = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
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
  implementation("androidx.webkit:webkit:1.12.1")
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Image Loading
  implementation("io.coil-kt:coil:2.7.0")
}
