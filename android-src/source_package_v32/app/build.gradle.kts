plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services") apply false
}

val demoBuild = providers.gradleProperty("demoBuild")
    .orNull
    ?.toBooleanStrictOrNull()
    ?: false

if (!demoBuild) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.nanocomm.nanosmart.eventos"
    compileSdk = 35

    defaultConfig {
        applicationId = if (demoBuild) {
            "com.nanocomm.nanosmart.eventos.demo"
        } else {
            "com.nanocomm.nanosmart.eventos"
        }
        minSdk = 23
        targetSdk = 35
        versionCode = 32
        versionName = if (demoBuild) "3.8.0-demo" else "3.8.0"

        buildConfigField("boolean", "DEMO_MODE", demoBuild.toString())
        manifestPlaceholders["appLabel"] = if (demoBuild) {
            "NanoSmart Demo"
        } else {
            "NanoSmart Eventos"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug").apply {
                enableV1Signing = true
                enableV2Signing = true
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.16.0"))
    implementation("com.google.firebase:firebase-messaging")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
