plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.app.videoeditor"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.app.videoeditor"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    // Media3 (video playback in the editor)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.common)

    // FFmpeg (render stickers onto the video and export)
    implementation(libs.ffmpeg.kit.full)
    // The maintained fork does not declare this transitive dependency in its POM
    implementation("com.arthenica:smart-exception-java:0.2.1")

    // CameraX
    implementation ("androidx.camera:camera-core:1.6.2")
    implementation ("androidx.camera:camera-camera2:1.6.2")
    implementation ("androidx.camera:camera-lifecycle:1.6.2")
    implementation ("androidx.camera:camera-video:1.6.2")
    implementation ("androidx.camera:camera-view:1.6.2")
    implementation ("androidx.camera:camera-extensions:1.6.2")
}