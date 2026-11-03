plugins {
    alias(libs.plugins.android.application)
}

val signingEnvironment = listOf(
    "CAMERA_KEYSTORE", "CAMERA_KEY_ALIAS", "CAMERA_STORE_PASS", "CAMERA_KEY_PASS"
).associateWith { providers.environmentVariable(it).orNull }
val hasReleaseSigning = signingEnvironment.values.all { !it.isNullOrBlank() }

val prepareLicenseAssets by tasks.registering(Sync::class) {
    from(rootProject.file("LICENSE")) { rename { "LICENSE.txt" } }
    from(rootProject.file("LICENSES/Shizuku-API.txt")) { rename { "Shizuku-API-LICENSE.txt" } }
    from(rootProject.file("LICENSES/Third-party.txt"))
    into(layout.buildDirectory.dir("generated/licenseAssets"))
}

android {
    namespace = "br.com.redesurftank.havalcamera"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "br.com.redesurftank.havalcamera"
        minSdk = 28
        targetSdk = 28
        versionCode = 3
        versionName = "0.3"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { targets += "camera_stream" }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { aidl = true }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging { jniLibs.useLegacyPackaging = true }
    sourceSets.getByName("main").assets.srcDir(prepareLicenseAssets)
    lint { disable += "ExpiredTargetSdkVersion" }

    signingConfigs {
        create("cameraRelease") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(signingEnvironment.getValue("CAMERA_KEYSTORE")!!)
                require(storeFile!!.isFile) { "CAMERA_KEYSTORE must point to an existing keystore." }
                keyAlias = signingEnvironment.getValue("CAMERA_KEY_ALIAS")
                storePassword = signingEnvironment.getValue("CAMERA_STORE_PASS")
                keyPassword = signingEnvironment.getValue("CAMERA_KEY_PASS")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("cameraRelease")
        }
    }
}

tasks.configureEach {
    if (name == "validateSigningRelease") {
        doFirst {
            require(hasReleaseSigning) {
                "Release signing requires CAMERA_KEYSTORE, CAMERA_KEY_ALIAS, CAMERA_STORE_PASS and CAMERA_KEY_PASS."
            }
        }
    }
}

dependencies {
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
}
