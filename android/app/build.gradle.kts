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

// AdMob IDs. Debug builds always use Google's published test IDs, so development
// never requests live ads. Release builds read the real IDs from Gradle properties
// (for example ~/.gradle/gradle.properties or ORG_GRADLE_PROJECT_* variables), which
// are never committed. validateReleaseAdMobIds stops a release build that lacks them
// or that would ship a test ID.
val googleTestAdMobAppId = "ca-app-pub-3940256099942544~3347511713"
val googleTestQuestionDetailBannerId = "ca-app-pub-3940256099942544/9214589741"
val releaseAdMobAppId = providers.gradleProperty("lid.admob.appId").orElse("")
val releaseQuestionDetailBannerId =
    providers.gradleProperty("lid.admob.questionDetailBannerId").orElse("")

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

        // Starts instrumented tests with ads disabled, so no test makes an ad request.
        testInstrumentationRunner =
            "com.nextdaymobilestudio.lebenindeutschland.testing.AdsDisabledTestRunner"
    }

    buildTypes {
        debug {
            manifestPlaceholders["admobAppId"] = googleTestAdMobAppId
            buildConfigField(
                "String",
                "ADMOB_QUESTION_DETAIL_BANNER_ID",
                "\"$googleTestQuestionDetailBannerId\"",
            )
        }
        release {
            manifestPlaceholders["admobAppId"] = releaseAdMobAppId.get()
            buildConfigField(
                "String",
                "ADMOB_QUESTION_DETAIL_BANNER_ID",
                "\"${releaseQuestionDetailBannerId.get()}\"",
            )
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
        buildConfig = true
        compose = true
    }
    androidResources {
        generateLocaleConfig = true
    }
}

val validateReleaseAdMobIds by tasks.registering {
    description = "Fails a release build without real AdMob IDs or with Google's test IDs."
    val appId = releaseAdMobAppId
    val bannerId = releaseQuestionDetailBannerId
    doLast {
        val problems = buildList {
            if (!Regex("""ca-app-pub-\d{16}~\d{10}""").matches(appId.get())) {
                add("lid.admob.appId must be an AdMob app ID (ca-app-pub-…~…)")
            }
            if (!Regex("""ca-app-pub-\d{16}/\d{10}""").matches(bannerId.get())) {
                add("lid.admob.questionDetailBannerId must be an AdMob ad unit ID (ca-app-pub-…/…)")
            }
            if (listOf(appId.get(), bannerId.get()).any { it.startsWith("ca-app-pub-3940256099942544") }) {
                add("release builds must not use Google's test AdMob IDs")
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Release AdMob configuration is invalid:\n- " + problems.joinToString("\n- ") +
                    "\nSet the properties in ~/.gradle/gradle.properties; never commit them.",
            )
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleaseAdMobIds)
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
    implementation(libs.google.mobile.ads)
    implementation(libs.google.user.messaging.platform)
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
