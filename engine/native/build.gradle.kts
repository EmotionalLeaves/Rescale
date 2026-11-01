import java.net.URI

plugins {
    alias(libs.plugins.android.library)
}

// Pinned ncnn release. Prebuilt static libs are downloaded at build time (not committed).
val ncnnVersion = "20261020"
val ncnnDir = layout.projectDirectory.dir("third_party/ncnn-$ncnnVersion-android-vulkan")

// -PncnnSource=<path to an ncnn checkout with submodules> builds ncnn from source instead
// (scripts/fetch-ncnn-source.sh). F-Droid builds use this; normal builds use the prebuilt.
val ncnnSource: String? = (findProperty("ncnnSource") as String?)?.let { rootProject.file(it).absolutePath }

// -Pabis=arm64-v8a,x86_64 limits the ABIs (per-ABI F-Droid builds, faster local builds).
val abiList: List<String> = (findProperty("abis") as String?)?.split(",")?.map { it.trim() }
    ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")

val fetchNcnn by tasks.registering {
    description = "Downloads the ncnn Android Vulkan prebuilt into third_party/"
    outputs.dir(ncnnDir)
    onlyIf { ncnnSource == null && !ncnnDir.asFile.resolve("arm64-v8a/lib/libncnn.a").exists() }
    doLast {
        val zip = layout.buildDirectory.file("ncnn.zip").get().asFile
        zip.parentFile.mkdirs()
        val url = "https://github.com/Tencent/ncnn/releases/download/$ncnnVersion/ncnn-$ncnnVersion-android-vulkan.zip"
        logger.lifecycle("Downloading $url")
        URI(url).toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
        project.copy {
            from(project.zipTree(zip))
            into(layout.projectDirectory.dir("third_party"))
        }
        zip.delete()
    }
}

android {
    namespace = "app.rescale.engine.nativebridge"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        minSdk = 26
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    if (ncnnSource != null) "-DNCNN_SOURCE_DIR=$ncnnSource" else "-DNCNN_ROOT=${ncnnDir.asFile.absolutePath}",
                    "-DANDROID_STL=c++_static",
                )
            }
        }
        ndk { abiFilters += abiList }
        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

tasks.named("preBuild") { dependsOn(fetchNcnn) }

dependencies {
    implementation(libs.androidx.core.ktx)
}
