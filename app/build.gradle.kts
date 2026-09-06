plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.morselink.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.morselink.app"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        // Demo keystore committed with the project so the APK stays installable.
        create("release") {
            storeFile = file("../keystore/morse-link.jks")
            storePassword = "morselink"
            keyAlias = "morselink"
            keyPassword = "morselink"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt",
                "META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/INDEX.LIST")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.bundles.androidx.base)
    implementation(libs.bundles.androidx.ui)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.work)
    implementation(libs.androidx.hilt.work)
    implementation(libs.material)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(project(":core:core-ui"))
    implementation(project(":core:core-data"))
    implementation(project(":core:core-media"))
    implementation(project(":core:core-transfer"))
    implementation(project(":core:core-network"))

    implementation(project(":feature:feature-dashboard"))
    implementation(project(":feature:feature-send"))
    implementation(project(":feature:feature-receive"))
    implementation(project(":feature:feature-transfer-ui"))
    implementation(project(":feature:feature-filemanager"))
    implementation(project(":feature:feature-webshare"))
    implementation(project(":feature:feature-settings"))
}
