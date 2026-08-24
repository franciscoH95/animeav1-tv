import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

// Optional release signing. Drop a keystore.properties (storeFile / storePassword / keyAlias /
// keyPassword) in the project root to sign release builds with a stable key. Keep it out de git.
// ⚠️ Sin ese fichero el release sale **SIN FIRMAR** (`app-release-unsigned.apk`, comprobado
// ejecutando `assembleRelease`), no firmado con la clave de debug: no se puede ni instalar.
// De dónde saca la app el manifiesto de actualizaciones (OTA). Por defecto, el ÚLTIMO release del
// repo de GitHub: esa URL no cambia al publicar una versión nueva, GitHub siempre la resuelve al
// release más reciente. Se configura con `githubRepo=usuario/repo` en gradle.properties, y se puede
// apuntar a otro sitio para probar: `./gradlew :app:assembleDebug -PupdateUrl=http://10.0.2.2:8000/update.json`
val githubRepo = (project.findProperty("githubRepo") as String?)?.trim().orEmpty()
    .ifBlank { "TU-USUARIO/TU-REPO" }
val updateManifestUrl = (project.findProperty("updateUrl") as String?)?.trim().orEmpty()
    .ifBlank { "https://github.com/$githubRepo/releases/latest/download/update.json" }

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.animeav1"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.animeav1"
        minSdk = 21
        targetSdk = 34
        versionCode = 13
        versionName = "1.5.3"

        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$updateManifestUrl\"")
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    // Lint had never been run here, which is exactly how a NewApi call (Resources.getColor with
    // a Theme, API 23, against minSdk 21) shipped as a guaranteed crash on Lollipop. Failing the
    // build is the point; the disabled checks below are the project's known false positives.
    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = false
        disable += setOf(
            "RestrictedApi",  // fires on super.dispatchKeyEvent() (androidx-internal annotation)
            "UseAppTint",     // ImageButton tint on the player controls
        )
    }

    // buildConfig no viene activado por defecto en AGP 8: hace falta para UPDATE_MANIFEST_URL.
    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    kapt { correctErrorTypes = true }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.fragment:fragment-ktx:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("io.coil-kt:coil:2.6.0")
    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    // Tests (JVM only — the two pieces with a history of breaking are pure logic).
    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub that throws; a real implementation earlier on the
    // unit-test classpath shadows it so SvelteKitDecoder can be exercised for real.
    testImplementation("org.json:json:20240303")
}
