import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.shohei0205.yamamuki"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.shohei0205.yamamuki"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.5.0"
    }

    // 配布版の署名鍵。キーストアとパスワードはリポジトリに入れず、PC 内の properties ファイルから読む
    // (既定は ~/.android/yamamuki-release.properties。環境変数 YAMAMUKI_SIGNING_PROPERTIES で変えられる)。
    // ファイルが無ければ署名せずにビルドする。storeFile を相対パスで書いたときは、properties ファイルのある
    // フォルダから探す(リポジトリの中でフォルダを動かしても、鍵の場所がずれないように)。
    val signingPropsFile = (System.getenv("YAMAMUKI_SIGNING_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.android/yamamuki-release.properties")
        .let(::file)
        .takeIf { it.exists() }
    val signingProps = signingPropsFile?.let { f -> Properties().apply { f.inputStream().use(::load) } }

    // 開発版が読む山データの取得先。既定は正式版(yamamuki-data の main が公開する peaks/、配布版と同じ)。
    // 開発版のデータ(dev が公開する points/osm-peaks-dev/)を試すときは、Gradle のプロパティ peakDataSource=dev を付ける
    // (例: ./gradlew :app:installDebug -PpeakDataSource=dev。~/.gradle/gradle.properties に書いてもよい)。
    val peakDataSource = providers.gradleProperty("peakDataSource").getOrElse("stable")
    require(peakDataSource in setOf("stable", "dev")) { "peakDataSource は stable か dev を指定してください: $peakDataSource" }

    signingConfigs {
        if (signingPropsFile != null && signingProps != null) {
            create("release") {
                storeFile = signingPropsFile.parentFile.resolve(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // 山データの manifest は、配布版は正式版、開発版は peakDataSource で選んだほうを読む(core の PeakData.manifestUrl)。
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("boolean", "PEAK_DATA_DEV", "false")
        }
        // 配布版と同じ端末に入れられるよう、開発版は別のアプリにする(名前は src/debug の strings.xml)。
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "PEAK_DATA_DEV", (peakDataSource == "dev").toString())
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("io.github.shohei0205.yamamuki:core")

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
