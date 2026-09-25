import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from android/keystore.properties (never committed), e.g.
//   storeFile=../vistile-release.jks
//   storePassword=...
//   keyAlias=vistile
//   keyPassword=...
// Without it, release builds fall back to the debug key so the project still builds from a fresh clone,
// but such an APK must not be published: updates only install over an APK signed with the same key.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.mints.vistile"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.mints.vistile"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

// Name release APKs for GitHub Releases: Vistile-1.0.0.apk
@Suppress("DEPRECATION")
android.applicationVariants.all {
    val variant = this
    if (variant.buildType.name == "release") variant.outputs.all {
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "Vistile-${variant.versionName}.apk"
    }
}

if (!hasReleaseKey) gradle.taskGraph.whenReady {
    if (allTasks.any { it.name.contains("Release") })
        logger.warn("Vistile: android/keystore.properties not found - release APK is signed with the DEBUG key; don't publish it.")
}
