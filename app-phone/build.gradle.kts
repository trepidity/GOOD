import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    // Phone and watch MUST share applicationId and signing key, or the Data Layer won't connect them.
    namespace = "com.trepidity.good.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.trepidity.good"
        minSdk = 34 // OnePlus 12 on OxygenOS 16 (Android 16); no need to support older phones
        targetSdk = 36
        versionCode = 4
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

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:wake"))
    implementation(project(":core:sync"))
    implementation(project(":core:ring"))
    implementation(project(":core:lcd"))
    implementation(project(":core:sleep"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.play.services.wearable)
    implementation(libs.health.connect.client)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime.ktx)
    implementation(libs.fragment.ktx) // registerForActivityResult needs ≥ 1.3; Play services pulls an older one
    implementation(libs.play.services.location)

    testImplementation(libs.junit)
}
