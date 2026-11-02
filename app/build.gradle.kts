plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.findroid"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.findroid"
        minSdk = 30
        targetSdk = 36
        versionCode = 31
        versionName = "0.31.0"
        buildConfigField("String", "UPDATE_REPOSITORY", "\"moikdev/findroid\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("preview") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            versionNameSuffix = "-preview"
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        animationsDisabled = true
    }
}

dependencies {
    constraints {
        implementation("com.google.guava:guava:33.7.2-android") {
            because("CVE-2026-102554: bound allocations during Guava deserialization")
        }
    }
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui:1.8.3")
    implementation("androidx.compose.ui:ui-tooling-preview:1.8.3")
    implementation("androidx.compose.foundation:foundation:1.8.3")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.commonmark:commonmark:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.30.0")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.30.0")
    implementation("com.google.code.gson:gson:2.15.0")
    implementation("com.google.re2j:re2j:1.8")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Keep the PDF reader's cryptographic dependencies aligned with the maintained release.
    implementation("org.bouncycastle:bcprov-jdk15to18:1.86")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.86")
    implementation("org.bouncycastle:bcutil-jdk15to18:1.86")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling:1.8.3")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.8.3")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.8.3")
}

// APK fixtures use this checkout's debug certificate; none enter a shipping variant.
val updateFixtureDirectory = layout.buildDirectory.dir("generated/update-fixtures")
val generateUpdateFixtures by tasks.registering(Exec::class) {
    dependsOn("validateSigningDebug")
    val debugKey = android.signingConfigs.getByName("debug").storeFile!!
    inputs.file(rootProject.file("scripts/build-update-fixtures.py"))
    inputs.file(debugKey)
    inputs.property("versionCode", android.defaultConfig.versionCode!!)
    outputs.dir(updateFixtureDirectory)
    commandLine("python3", rootProject.file("scripts/build-update-fixtures.py"),
        "--sdk", androidComponents.sdkComponents.sdkDirectory.get().asFile, "--output", updateFixtureDirectory.get().asFile,
        "--keystore", debugKey, "--java-home", System.getProperty("java.home"),
        "--version-code", android.defaultConfig.versionCode!! + 1)
}
android.sourceSets.getByName("androidTest").assets.directories.add(updateFixtureDirectory.get().dir("assets").asFile.absolutePath)
tasks.configureEach {
    if (name in setOf("mergeDebugAndroidTestAssets", "generateDebugAndroidTestLintModel", "lintAnalyzeDebugAndroidTest"))
        dependsOn(generateUpdateFixtures)
}
