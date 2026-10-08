plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.superstudent.core.database"
    compileSdk = 37
    defaultConfig {
        minSdk = 28
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}


dependencies {
    api(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:security"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real SQLite on the JVM, so MIGRATION_2_3's shipped statements can be executed against a
    // database built from the exported v2 schema without an emulator.
    testImplementation("org.xerial:sqlite-jdbc:3.41.2.2")
}
