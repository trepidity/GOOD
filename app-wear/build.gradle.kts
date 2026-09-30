import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    // Same applicationId and signing key as :app-phone, or the Data Layer won't pair them.
    namespace = "com.trepidity.good.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.trepidity.good"
        minSdk = 33       // Wear OS 4+; the OnePlus Watch 2R runs Wear OS 5 (Android 14)
        targetSdk = 35    // Play's Wear OS requirement from Aug 31, 2026
        versionCode = 1004
        versionName = "0.4.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:wake"))
    implementation(project(":core:sleep"))
    implementation(project(":core:sync"))
    implementation(project(":core:ring"))
    implementation(project(":core:lcd"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)

    implementation(libs.play.services.wearable)
    implementation(libs.health.services.client)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.material)
    implementation(libs.wear.protolayout.expression)
    implementation(libs.wear.complications.data.source.ktx)
}
