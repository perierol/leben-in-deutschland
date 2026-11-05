plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Local development uses an ignored google-services.json. Keeping application
// of the plugin conditional lets pull-request CI validate the project without
// requiring Firebase configuration or exposing it in source control.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

room {
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = "com.nextdaymobilestudio.lebenindeutschland"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.nextdaymobilestudio.lebenindeutschland"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        generateLocaleConfig = true
    }
}

// firebase-auth 25.x carries Kotlin 2.4 metadata while this module compiles with
// Kotlin 2.2.10, which the compiler rejects by default. Only the library's Java
// surface is used here (FirebaseAuth, signInAnonymously, currentUser), so skipping
// the check is contained. Remove it when the project moves to Kotlin 2.4 or newer.
tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java)
    .configureEach {
        compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
    }

dependencies {
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
