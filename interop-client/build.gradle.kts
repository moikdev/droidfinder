plugins { id("com.android.application") }
android {
    namespace = "dev.droidfinder.interop"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.droidfinder.interop"
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
