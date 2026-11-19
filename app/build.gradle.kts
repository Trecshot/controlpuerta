plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.gms.google.services)
}

android {
    namespace = "com.example.controlpuerta"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.controlpuerta"
        // 26: requerido por PBKDF2WithHmacSHA256 (hash de contraseñas con sal).
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "role"
    productFlavors {
        create("user") {
            dimension = "role"
            isDefault = true
        }
        create("admin") {
            dimension = "role"
            applicationIdSuffix = ".admin"
        }
    }

    buildTypes {
        debug {
            // El modo simulación (escribe en Firestore sin hardware) solo existe en compilaciones de desarrollo.
            buildConfigField("boolean", "ALLOW_SIMULATION", "true")
        }
        release {
            buildConfigField("boolean", "ALLOW_SIMULATION", "false")
            optimization {
                enable = true
                packageScope = setOf(
                    "com.example.controlpuerta.**",
                    "androidx.**",
                    "kotlin.**",
                    "kotlinx.**"
                )
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.firebase.firestore)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
