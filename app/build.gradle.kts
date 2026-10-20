import java.net.URLEncoder

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Microsoft sign-in settings come from DocScan/.env (or the environment), never from source. See .env.example.
val dotEnv = rootProject.file(".env").takeIf { it.exists() }?.readLines()
    ?.mapNotNull { l -> l.trim().takeIf { it.isNotEmpty() && !it.startsWith("#") && '=' in it } }
    ?.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    .orEmpty()
fun env(key: String): String = System.getenv(key)?.takeIf { it.isNotBlank() } ?: dotEnv[key]?.takeIf { it.isNotBlank() }
    ?: throw GradleException("$key is not set. Copy .env.example to .env and fill it in.")
val clientId = env("APPLICATION_CLIENT_ID")
val signatureHash = env("SIGNATURE_HASH")

android {
    namespace = "ai.bonsen.docscan"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.bonsen.docscan"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Phones are arm; dropping x86 keeps the APK small (OpenCV ships native libs per ABI).
        // Redirect path in AndroidManifest.xml (BrowserTabActivity).
        manifestPlaceholders["msalSignatureHash"] = signatureHash

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the APK installs directly on any phone.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// Writes res/raw/msal_config.json into build/ so the client ID lives only in .env.
val msalConfigDir = layout.buildDirectory.dir("generated/msal/res")
val generateMsalConfig by tasks.registering {
    inputs.property("clientId", clientId)
    inputs.property("signatureHash", signatureHash)
    outputs.dir(msalConfigDir)
    doLast {
        val f = msalConfigDir.get().file("raw/msal_config.json").asFile
        f.parentFile.mkdirs()
        f.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(mapOf(
            "client_id" to clientId,
            "authorization_user_agent" to "DEFAULT",
            "redirect_uri" to "msauth://ai.bonsen.docscan/" + URLEncoder.encode(signatureHash, "UTF-8"),
            "account_mode" to "SINGLE",
            "broker_redirect_uri_registered" to true,
            "authorities" to listOf(mapOf("type" to "AAD", "audience" to mapOf("type" to "AzureADMultipleOrgs"), "default" to true)),
        ))))
    }
}
android.sourceSets["main"].res.srcDir(msalConfigDir)
tasks.named("preBuild") { dependsOn(generateMsalConfig) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // On-device image processing (edge detection, perspective fix, contrast cleanup). Fully offline.
    implementation("org.opencv:opencv:4.9.0")
    // Page-corner model (assets/doccorner256.tflite).
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    // Microsoft sign-in and background SharePoint upload that waits for a network.
    implementation("com.microsoft.identity.client:msal:8.5.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Google sign-in (Drive access token) for the Google Drive backup.
    implementation("com.google.android.gms:play-services-auth:21.1.1")
}
