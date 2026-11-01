import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// versionName comes from the git tag in CI (v1.2.3 -> 1.2.3), else this default.
val appVersionName: String = (findProperty("versionName") as String?) ?: "0.1.0"
val appVersionCode: Int = appVersionName.split(".").map { it.toIntOrNull() ?: 0 }
    .let { (it.getOrElse(0) { 0 } * 10000) + (it.getOrElse(1) { 0 } * 100) + it.getOrElse(2) { 0 } }

// Release signing, first match wins:
//  1. env vars (CI with the KEYSTORE_B64 secret), 2. git-ignored keystore.properties,
//  3. keystore/rescale-public.jks: a deliberately PUBLIC key so anyone's builds install
//     over each other. Fine for a hobby sideload app; set the secret for a private key.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val publicKeystore = mapOf(
    "storeFile" to rootProject.file("keystore/rescale-public.jks").path,
    "storePassword" to "rescale-public",
    "keyAlias" to "rescale",
    "keyPassword" to "rescale-public",
)
val usePublicKey = System.getenv("RESCALE_KEYSTORE").isNullOrBlank() && !keystoreProps.containsKey("storeFile")
fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: keystoreProps.getProperty(prop)
        ?: if (usePublicKey) publicKeystore[prop] else null

android {
    namespace = "app.rescale"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.rescale"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        val storeFile = signingValue("RESCALE_KEYSTORE", "storeFile")
        if (storeFile != null) {
            create("release") {
                this.storeFile = file(storeFile)
                storePassword = signingValue("RESCALE_STORE_PASSWORD", "storePassword")
                keyAlias = signingValue("RESCALE_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("RESCALE_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    androidResources {
        // ncnn reads model files from disk; keep them uncompressed so they can be measured/copied fast.
        noCompress += listOf("bin", "param")
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    dependenciesInfo {
        // F-Droid/IzzyOnDroid can't verify Google's encrypted dependency blob.
        includeInApk = false
        includeInBundle = false
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// Per-ABI versionCode offsets so split APKs upgrade cleanly (universal = 0).
val abiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86_64" to 3)
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find {
                it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI
            }?.identifier
            val offset = abiCodes[abi] ?: 0
            output.versionCode.set(appVersionCode * 10 + offset)
        }
    }
}

dependencies {
    implementation(project(":worker"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
