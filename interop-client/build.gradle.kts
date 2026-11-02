plugins { id("com.android.application") }
android {
    namespace = "dev.findroid.interop"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.findroid.interop"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
