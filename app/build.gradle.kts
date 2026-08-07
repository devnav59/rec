plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStoreFileProperty = providers.gradleProperty("RELEASE_STORE_FILE")
val releaseStorePasswordProperty = providers.gradleProperty("RELEASE_STORE_PASSWORD")
val releaseKeyAliasProperty = providers.gradleProperty("RELEASE_KEY_ALIAS")
val releaseKeyPasswordProperty = providers.gradleProperty("RELEASE_KEY_PASSWORD")

android {
    namespace = "com.devnav.rec"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.devnav.rec"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            // The CI workflow supplies these values only when signing secrets are configured.
            val storeFilePath = releaseStoreFileProperty.orNull
            if (!storeFilePath.isNullOrBlank()) {
                storeFile = file(storeFilePath)
                storePassword = releaseStorePasswordProperty.orNull
                keyAlias = releaseKeyAliasProperty.orNull
                keyPassword = releaseKeyPasswordProperty.orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Without signing properties Gradle deliberately emits an unsigned release APK.
            // That keeps local and GitHub Action builds usable without committing a keystore.
            if (!releaseStoreFileProperty.orNull.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // On-device OCR; captured pixels never leave the phone.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
}
