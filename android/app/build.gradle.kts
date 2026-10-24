plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Une seule version pour la PWA et l'APK : celle de APP_VERSION dans index.html
val webRoot = rootProject.file("..")
val appVersion = Regex("APP_VERSION\\s*=\\s*'([^']+)'").find(File(webRoot, "index.html").readText())!!.groupValues[1]
val appVersionCode = appVersion.split(".").map { it.toInt() }.let { (a, b, c) -> a * 10000 + b * 100 + c }

android {
    namespace = "com.koala.label"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.koala.label"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        create("release") {
            storeFile = file("koalabel.jks")
            storePassword = "koalabel"
            keyAlias = "koalabel"
            keyPassword = "koalabel"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { buildConfig = true }

    // la page web (index.html + bibliothèques) est embarquée dans l'APK
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("web").get().asFile)
}

val copyWeb by tasks.registering(Copy::class) {
    from(webRoot) { include("index.html", "icon-192.png", "lib/**") }
    into(layout.buildDirectory.dir("web/www"))
}
tasks.named("preBuild") { dependsOn(copyWeb) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
}
