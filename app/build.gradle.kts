import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.materacitypass"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.materacitypass"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Legge i segreti da local.properties
        val props = Properties()
        val lp = rootProject.file("local.properties")
        if (lp.exists()) {
            props.load(FileInputStream(lp))
        }

        buildConfigField("String", "AIRTABLE_API_KEY", "\"${props.getProperty("AIRTABLE_API_KEY", "PASTE_HERE")}\"")
        buildConfigField("String", "AIRTABLE_BASE_ID", "\"${props.getProperty("AIRTABLE_BASE_ID", "BASE_HERE")}\"")
        buildConfigField("String", "AIRTABLE_TABLE_NAME", "\"NFC\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.foundation.android)
    implementation(libs.firebase.ai)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation(libs.okhttp3)
    implementation(libs.usb.serial)
}