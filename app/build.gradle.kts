plugins { id("com.android.application") }
android {
    namespace = "io.github.pointandyshoot.scopecam"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.pointandyshoot.scopecam"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true; warningsAsErrors = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies { testImplementation("junit:junit:4.13.2") }
