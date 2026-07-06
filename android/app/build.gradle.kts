plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

/// versionCode を git のコミット総数から計算する。
///
/// Play Console は同じ versionCode の AAB を再アップロードできないので、
/// 手で bump し忘れて何度もエラーになるのを避けるための自動採番。
/// - 各コミットで +1（HEAD のコミット数）
/// - shallow clone（depth=1）など履歴が無い環境では fallback (2) に落ちる
/// - GIME_VERSION_CODE 環境変数で明示的に上書き可能
private fun computeVersionCode(): Int {
    System.getenv("GIME_VERSION_CODE")?.toIntOrNull()?.let { return it }
    return try {
        val proc = ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .directory(rootDir.parentFile)  // android/ → repo root
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        val n = out.toIntOrNull()
        if (n != null && n > 0) n else 2
    } catch (_: Exception) {
        2
    }
}

android {
    namespace = "com.gime.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.msonrm.gime"
        minSdk = 28
        targetSdk = 35
        versionCode = computeVersionCode()
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            // CI では ANDROID_KEYSTORE_FILE に復号後のパスを渡す。
            // ローカル開発者は ~/.gradle/gradle.properties に RELEASE_* を置いても良い。
            val storeFilePath = System.getenv("ANDROID_KEYSTORE_FILE")
                ?: (project.findProperty("RELEASE_STORE_FILE") as String?)
            val storePasswordValue = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                ?: (project.findProperty("RELEASE_STORE_PASSWORD") as String?)
            val keyAliasValue = System.getenv("ANDROID_KEY_ALIAS")
                ?: (project.findProperty("RELEASE_KEY_ALIAS") as String?)
            val keyPasswordValue = System.getenv("ANDROID_KEY_PASSWORD")
                ?: (project.findProperty("RELEASE_KEY_PASSWORD") as String?)

            if (!storeFilePath.isNullOrBlank() && file(storeFilePath).exists()) {
                storeFile = file(storeFilePath)
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 鍵が用意されているときだけ署名（未設定でも configure 時にコケないように）
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/CONTRIBUTORS.md",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
            )
        }
    }
}

// Room のスキーマを git 管理するための出力先（KSP の DSL は android {} の外）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    // IME の InputView で ComposeView をホストするのに必要
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Logging（vendored KazumaProject converter が使用）
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Room（ユーザー辞書・学習）
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ML Kit On-Device Translation（VRChat OSC 二段送信用、初回はモデル ~30MB を WiFi 取得）
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")

    // OpenCC4j: ML Kit ZH（簡体）→ 繁體（台湾）後処理用。純 JVM 実装、JNI 不要、Apache 2.0
    implementation("com.github.houbb:opencc4j:1.14.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Unit tests (JVM)
    testImplementation("junit:junit:4.13.2")
}
