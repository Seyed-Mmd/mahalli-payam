plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ir.mahalipayam.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "ir.mahalipayam.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "API_BASE_URL", "\"${project.findProperty("apiBaseUrl") ?: ""}\"")
    }
    buildFeatures { buildConfig = true }
}
