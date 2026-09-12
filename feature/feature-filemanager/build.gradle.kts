plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.morselink.feature.filemanager"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
    }

    buildFeatures {
        viewBinding = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.bundles.androidx.base)
    implementation(libs.bundles.androidx.ui)
    implementation(project(":core:core-ui"))
    implementation(project(":core:core-media"))
    // ConnectionHolder (core-network) to hand picked files to a live session,
    // and TransferableFile (core-transfer) to describe them.
    implementation(project(":core:core-network"))
    implementation(project(":core:core-transfer"))
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.glide)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
