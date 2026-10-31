plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "pl.ambienthome.tv"
    compileSdk = 35
    defaultConfig {
        applicationId = "pl.ambienthome.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.5.0-beta1"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        debug { applicationIdSuffix = ".universaltest"; versionNameSuffix = "-test" }
        release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) }
    }
}
dependencies { testImplementation("junit:junit:4.13.2") }
