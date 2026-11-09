plugins {
    id("com.android.application")
}

android {
    namespace = "com.micomp.bydha"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.micomp.bydha"
        minSdk = 26
        // Kept low on purpose, like other BYD head-unit apps: newer target SDKs
        // add background-start limits that the boot receiver would trip over.
        //noinspection ExpiredTargetSdkVersion
        targetSdk = 25
        versionCode = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("BUILD_NUMBER") ?: "0")
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        // A fixed key kept in the repo so every CI build can update the installed app
        // without uninstalling it. It is not secret: anyone with repo access can sign with it.
        create("project") {
            storeFile = file("signing.keystore")
            storePassword = "bydha-public"
            keyAlias = "bydha"
            keyPassword = "bydha-public"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("project")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("project")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Extract libtailscale.so to the native library dir so the shell user can exec it.
        jniLibs { useLegacyPackaging = true }
        resources { excludes += listOf("META-INF/*.kotlin_module", "META-INF/versions/**") }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("dev.mobile:dadb:1.2.8")
}
