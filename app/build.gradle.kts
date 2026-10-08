import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// ZLQ-115: the demo release keystore and its passwords are secrets and must never be committed.
// They are injected here from the gitignored `local.properties` (or, failing that, the environment)
// so the signing material stays off the repo, out of the APK's provenance, and out of any comment.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
val signingProp: (String) -> String? = { name ->
    keystoreProperties.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.takeIf { it.isNotBlank() }
}

android {
    namespace = "com.superstudent.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.superstudent.app"
        minSdk = 28
        // ZLQ-111: `compileSdk` stays on 37 so the new APIs remain visible at compile time, but the
        // behaviour changes API 37 gates behind `targetSdk` are not what this release was tested
        // against, so the app keeps declaring 36.
        targetSdk = 36
        // ZLQ-149: versionCode 9 / versionName "1.0.0-rc9" already circulated as the ZLQ-143
        // integration candidate. rc10 is that baseline plus the ZLQ-146 x ZLQ-147 merge and the
        // ZLQ-151 ssVis axis-aligned-edge fix (tip 67b6115), so it takes the next monotonic code and
        // a distinct versionName; two debug APKs sharing both identity fields install over each other
        // silently and dumpsys prints the same code for both, so QA cannot attribute the ZLQ-144
        // joint-regression result to a specific package (ZLQ-115 / ZLQ-96). rc9 stays withheld.
        versionCode = 10
        versionName = "1.0.0-rc10"
    }

    signingConfigs {
        create("release") {
            val storePath = signingProp("SS_STORE_FILE")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = signingProp("SS_STORE_PASSWORD")
                keyAlias = signingProp("SS_KEY_ALIAS")
                keyPassword = signingProp("SS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // ZLQ-115: sign release with the injected demo keystore instead of the throwaway debug key.
            // When the keystore is not configured the config is left unset and AGP emits an unsigned APK
            // rather than silently signing with debug — a release candidate must never ship on the debug key.
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}


dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:database"))
    implementation(project(":core:security"))
    implementation(project(":core:designsystem"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.coil.compose)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
