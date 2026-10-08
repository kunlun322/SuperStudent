plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.superstudent.core.network"
    compileSdk = 37
    defaultConfig {
        minSdk = 28
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}


dependencies {
    api(project(":core:model"))
    api(project(":core:security"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    api(libs.okhttp.sse)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
