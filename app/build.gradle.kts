import com.android.build.api.variant.FilterConfiguration
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

/**
 * Khóa ký bản release — KHÔNG nằm trong repo.
 *  - Máy dev: file keystore.properties (mặc định %LOCALAPPDATA%/phim275-signing/, hoặc đường dẫn trong
 *    biến môi trường PHIM275_KEYSTORE_PROPERTIES).
 *  - CI: biến môi trường PHIM275_STORE_FILE / PHIM275_STORE_PASSWORD / PHIM275_KEY_ALIAS / PHIM275_KEY_PASSWORD.
 * Không có khóa → bản release được ký bằng khóa debug (chỉ để thử, không cài đè được bản chính thức).
 */
val releaseSigning: Map<String, String>? = run {
    System.getenv("PHIM275_STORE_FILE")?.takeIf { it.isNotBlank() }?.let { file ->
        return@run mapOf(
            "storeFile" to file,
            "storePassword" to System.getenv("PHIM275_STORE_PASSWORD").orEmpty(),
            "keyAlias" to (System.getenv("PHIM275_KEY_ALIAS") ?: "phim275"),
            "keyPassword" to System.getenv("PHIM275_KEY_PASSWORD").orEmpty(),
        )
    }
    val propsPath = System.getenv("PHIM275_KEYSTORE_PROPERTIES")
        ?: System.getenv("LOCALAPPDATA")?.let { "$it/phim275-signing/keystore.properties" }
    val propsFile = propsPath?.let(::File)?.takeIf { it.isFile } ?: return@run null
    val props = Properties().apply { propsFile.inputStream().use(::load) }
    props.stringPropertyNames().associateWith { props.getProperty(it) }
}

// Mã phiên bản cơ sở; mỗi ABI cộng thêm hậu tố để APK arm64 luôn "mới hơn" APK v7a.
// PHẢI tăng mỗi lần phát hành: bộ tự cập nhật so sánh versionCode / 10.
val baseVersionCode = 17
val abiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86_64" to 3)

android {
    namespace = "phim275.tv.com"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "phim275.tv.com"
        minSdk = 26
        targetSdk = 36
        versionCode = baseVersionCode
        versionName = "0.14.0"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*abiCodes.keys.toTypedArray())
            isUniversalApk = false
        }
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        // Mặc định aapt bỏ qua thư mục bắt đầu bằng "_" → mất "_locales" của uBO (extension lỗi).
        // Giữ lại các mẫu mặc định khác, chỉ bỏ "<dir>_*" và ".*".
        ignoreAssetsPatterns.clear()
        ignoreAssetsPatterns += listOf("!.svn", "!.git", "!.ds_store", "!*.scc", "!CVS", "!thumbs.db", "!picasa.ini", "!*~")
    }

    packaging {
        // Nén thư viện native (libxul ~112MB → ~40MB) để APK tải về nhẹ hơn nhiều.
        // Đổi lại: lúc cài, Android giải nén ra bộ nhớ máy (tốn thêm dung lượng lưu trữ, không tốn RAM).
        jniLibs.useLegacyPackaging = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
            val suffix = abiCodes[abi] ?: 0
            output.versionCode.set(baseVersionCode * 10 + suffix)
        }
    }
}

dependencies {
    implementation(libs.geckoview)
    testImplementation(libs.junit)
    testImplementation(libs.orgjson)
}
