plugins {
    id("com.android.library")
    id("kotlin-parcelize")
    id("kotlinx-serialization")
}

android {
    namespace = "xyz.doikki.android.dkplayer"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets {
        named("main") {
            jniLibs.srcDirs("libs")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}



dependencies {
    api(project(":lib-base"))
    api(libs.androidx.annotation)
    testImplementation(libs.test.junit)
    testImplementation(libs.test.mockk)
}
