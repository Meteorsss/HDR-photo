plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.meteorsss.hdrphoto"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.meteorsss.hdrphoto"
        minSdk = 34
        targetSdk = 36
        testInstrumentationRunner = "com.meteorsss.hdrphoto.InteractionProbe"
        versionCode = 12
        versionName = "1.1.10"
    }

    // PR builds never require credentials. Opt in only on a trusted release machine.
    val signingFile = providers.environmentVariable("HDR_KEYSTORE").orNull
    if (signingFile != null) {
        signingConfigs.create("distribution") {
            storeFile = file(signingFile)
            storePassword = providers.environmentVariable("HDR_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("HDR_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("HDR_KEY_PASSWORD").get()
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("distribution")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies { testImplementation("junit:junit:4.13.2") }
