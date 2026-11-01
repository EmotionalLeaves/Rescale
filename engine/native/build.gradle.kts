import java.net.URI

plugins {
    alias(libs.plugins.android.library)
}

// Pinned ncnn release. Prebuilt static libs are downloaded at build time (not committed).
val ncnnVersion = "20261020"
val ncnnDir = layout.projectDirectory.dir("third_party/ncnn-$ncnnVersion-android-vulkan")

val fetchNcnn by tasks.registering {
    description = "Downloads the ncnn Android Vulkan prebuilt into third_party/"
    outputs.dir(ncnnDir)
    onlyIf { !ncnnDir.asFile.resolve("arm64-v8a/lib/libncnn.a").exists() }
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
                    "-DNCNN_ROOT=${ncnnDir.asFile.absolutePath}",
                    "-DANDROID_STL=c++_static",
                )
            }
        }
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
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
