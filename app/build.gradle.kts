plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.burplite"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.burplite"
        minSdk = 26   // VpnService + modern TLS need API 26+
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    packaging {
        // Netty & BouncyCastle both ship META-INF files that collide during merge
        resources.excludes += setOf(
            "META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties",
            "META-INF/DEPENDENCIES",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        )
    }
}

dependencies {
    // Core UI
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("com.google.android.material:material:1.12.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.compose.material:material-icons-extended:1.6.8")

    // Serialization (used for JSON-encoding headers in Room)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Proxy engine
    implementation("io.netty:netty-codec-http:4.1.111.Final")
    implementation("io.netty:netty-handler:4.1.111.Final")
    implementation("io.netty:netty-transport:4.1.111.Final")
    implementation("io.netty:netty-buffer:4.1.111.Final")

    // Certificate generation for on-the-fly MITM leaf certs
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    // Local persistence for request/response history
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
}
