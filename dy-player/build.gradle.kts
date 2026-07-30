import com.android.build.api.artifact.ArtifactTransformationRequest
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifact
import me.lingci.gradle.localBuildConfigString

plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.parcelize)
    id("kotlinx-serialization")
    id("com.google.devtools.ksp")
}

val buglyAppId = project.localBuildConfigString("BUGLY_APP_ID")

android {
    namespace = "me.lingci.dy.player"
    compileSdk = libs.versions.compileSdk.get().toInt()

    flavorDimensions.add("mode")

    defaultConfig {
        applicationId = "me.lingci.dy.player"
        minSdk = 24
        // noinspection ExpiredTargetSdkVersion
        targetSdk = 28
        versionCode = 24
        versionName = "0.2.4"

        // Bugly APP ID，从 local.properties 读取，未配置时使用空字符串
        buildConfigField("String", "BUGLY_APP_ID", buglyAppId)

        multiDexEnabled = true

        ndk {
            // "armeabi", "armeabi-v7a", "arm64-v8a", "x86", "x86_64", "mips", "mips64"
            //noinspection ChromeOsAbiSupport
            abiFilters.add("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                //noinspection ChromeOsAbiSupport
                abiFilters.add("arm64-v8a")
                abiFilters.add("x86_64")
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            pickFirsts += setOf(
                "lib/arm64-v8a/libc++_shared.so",
                "lib/x86_64/libc++_shared.so",
                "lib/armeabi-v7a/libc++_shared.so",
                "lib/x86/libc++_shared.so"
            )
        }
    }


    productFlavors {
        create("prod") {
            dimension = "mode"

            manifestPlaceholders["APP_NAME"] = "@string/app_name"
            manifestPlaceholders["APP_ICON"] = "@mipmap/ic_launcher"
            manifestPlaceholders["APP_ICON_ROUND"] = "@mipmap/ic_launcher_round"

            buildConfigField("boolean", "isProd", "true")
        }
        create("beta") {
            dimension = "mode"

            applicationIdSuffix = ".debug"
            versionNameSuffix = "-d"
            manifestPlaceholders["APP_NAME"] = "@string/app_name_debug"
            manifestPlaceholders["APP_ICON"] = "@mipmap/ic_launcher_debug"
            manifestPlaceholders["APP_ICON_ROUND"] = "@mipmap/ic_launcher_round_debug"

            buildConfigField("boolean", "isProd", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    splits {
        abi {
            isEnable = true
            reset()
            //noinspection ChromeOsAbiSupport
            include("arm64-v8a")
            isUniversalApk = true
        }
    }
}

abstract class RenameDyPlayerApksTask : DefaultTask() {
    @get:InputDirectory
    abstract val inputApkFolder: DirectoryProperty

    @get:OutputDirectory
    abstract val outputApkFolder: DirectoryProperty

    @get:Input
    abstract val buildTypeName: Property<String>

    @get:Input
    abstract val flavorName: Property<String>

    @get:Internal
    abstract val transformationRequest: Property<ArtifactTransformationRequest<RenameDyPlayerApksTask>>

    @TaskAction
    fun rename() {
        transformationRequest.get().submit(this) { builtArtifact ->
            val abi = builtArtifact.filters.firstOrNull {
                it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI
            }?.identifier ?: return@submit copyArtifact(builtArtifact)

            val versionName = builtArtifact.versionName ?: "unknown"
            val outputFileName = when (buildTypeName.get()) {
                "release" -> {
                    if (flavorName.get() == "prod") {
                        "DyLike-${abi}-v${versionName}-${buildTypeName.get()}.apk"
                    } else {
                        "DyLike-${abi}-v${versionName}-${flavorName.get()}.apk"
                    }
                }

                else -> "DyLike-${abi}-v${versionName}-${buildTypeName.get()}.apk"
            }

            val outputFile = outputApkFolder.file(outputFileName).get().asFile
            builtArtifact.path.toFile().copyTo(outputFile, overwrite = true)
            outputFile
        }
    }

    private fun copyArtifact(builtArtifact: BuiltArtifact): java.io.File {
        val inputFile = builtArtifact.path.toFile()
        val outputFile = outputApkFolder.file(inputFile.name).get().asFile
        inputFile.copyTo(outputFile, overwrite = true)
        return outputFile
    }
}

androidComponents {
    onVariants { variant ->
        val renameTask = tasks.register<RenameDyPlayerApksTask>("rename${variant.name.replaceFirstChar(Char::titlecase)}Apks") {
            buildTypeName.set(variant.buildType ?: "")
            flavorName.set(variant.flavorName ?: "")
        }

        val apkTransformationRequest = variant.artifacts.use(renameTask)
            .wiredWithDirectories(
                RenameDyPlayerApksTask::inputApkFolder,
                RenameDyPlayerApksTask::outputApkFolder,
            )
            .toTransformMany(SingleArtifact.APK)

        renameTask.configure {
            transformationRequest.set(apkTransformationRequest)
        }
    }
}



dependencies {

    implementation(project(":lib-base"))
    implementation(project(":lib-player:player-ui"))
    implementation(project(":lib-player:player-exo"))
    implementation(project(":lib-player:player-mpv"))

    implementation(libs.bundles.androidx.navigation)
    implementation(libs.android.material)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.media)
    // bug
    implementation(libs.bugly)

    testImplementation(libs.test.junit)
    androidTestImplementation(libs.bundles.android.test)

}
