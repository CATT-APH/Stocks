import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

//Needed for Monarch Development
val jenkinsBuildCode: String = System.getenv("J_VERCODE") ?: "1"
val jenkinsBuildName: String = System.getenv("J_VERNAME") ?: "1.0"

// Twelve Data API key: loaded from a gitignored properties file, never a
// source literal. Empty string (not a crash) if the file is missing, so a
// fresh checkout without the file still compiles -- ChartActivity checks
// for blank and reports a clear "no API key configured" state instead.
val apiKeyPropertyFile = rootProject.file("apikeys.properties")
val twelveDataApiKey: String = if (apiKeyPropertyFile.exists()) {
    val props = Properties()
    apiKeyPropertyFile.inputStream().use { props.load(it) }
    props.getProperty("twelve_data_api_key", "")
} else ""

android {
    namespace = "org.aph.stocks"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.aph.stocks"
        minSdk = 33
        targetSdk = 33
        versionCode = jenkinsBuildCode.toInt()
        versionName = jenkinsBuildName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TWELVE_DATA_API_KEY", "\"$twelveDataApiKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
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
}

dependencies {
    implementation("com.humanware:KeySoftSDK:0.1.136") //Needed for Monarch Development
    // hilt-android removed -- was never actually wired up (no Hilt Gradle
    // plugin applied, @HiltAndroidApp was inert, zero @Inject/@Module usage
    // anywhere in the app). See StocksApplication.kt.
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Twelve Data calls use plain java.net.HttpURLConnection + org.json (both
    // built into Android) -- no HTTP client dependency, deliberately, to avoid
    // another Maven-resolution risk on a same-day build. lifecycleScope/coroutines
    // are available transitively the same way PlantWave already used them
    // without an explicit coroutines dependency.

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
