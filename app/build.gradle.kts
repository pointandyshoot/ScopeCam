plugins { id("com.android.application") }
android {
    namespace = "io.github.pointandyshoot.scopecam"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.pointandyshoot.scopecam"
        minSdk = 29
        targetSdk = 37
        versionCode = 4
        versionName = "0.1.3"
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = true
        warningsAsErrors = true
        // Pin the tested AGP/Gradle compatibility pair. Online upgrade advice must
        // not make an otherwise identical build fail when a new Gradle is released.
        disable += "AndroidGradlePluginVersion"
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies { testImplementation("junit:junit:4.13.2") }
