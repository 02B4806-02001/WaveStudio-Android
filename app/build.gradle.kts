plugins {
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.mhrri.wavestudio"
    compileSdk = 36 // Gradle 9.0+ 推荐简化写法

    defaultConfig {
        applicationId = "org.mhrri.wavestudio"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.13.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // 建议：不要把密码硬编码到仓库。
            // 这里从 gradle.properties（或环境变量）读取。
            val storeFilePath = (project.findProperty("RELEASE_STORE_FILE") as String?)
                ?: System.getenv("RELEASE_STORE_FILE")
            val storePassword = (project.findProperty("RELEASE_STORE_PASSWORD") as String?)
                ?: System.getenv("RELEASE_STORE_PASSWORD")
            val keyAlias = (project.findProperty("RELEASE_KEY_ALIAS") as String?)
                ?: System.getenv("RELEASE_KEY_ALIAS")
            val keyPassword = (project.findProperty("RELEASE_KEY_PASSWORD") as String?)
                ?: System.getenv("RELEASE_KEY_PASSWORD")

            if (!storeFilePath.isNullOrBlank()) {
                storeFile = file(storeFilePath)
            }
            if (!storePassword.isNullOrBlank()) storePassword?.let { this.storePassword = it }
            if (!keyAlias.isNullOrBlank()) keyAlias?.let { this.keyAlias = it }
            if (!keyPassword.isNullOrBlank()) keyPassword?.let { this.keyPassword = it }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // 如果没有提供 signing 参数，或者 keystore 文件不存在，回退到 debug 签名，确保能生成可安装的 APK
            val releaseConfig = signingConfigs.getByName("release")
            if (releaseConfig.storeFile != null && releaseConfig.storeFile!!.exists()) {
                signingConfig = releaseConfig
            } else {
                signingConfig = signingConfigs.getByName("debug")
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

    composeOptions {
        // Kotlin 2.3.20 匹配的 Compose 编译器版本
        kotlinCompilerExtensionVersion = "2.3.20"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Kotlin 2.3.0 适配的 JVM 工具链
kotlin {
    jvmToolchain(17)
}

dependencies {
    // 基础核心依赖（引用版本目录）
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose 核心依赖
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")

    // ViewModel + Compose 集成（直接写死版本号）
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    // 音频播放（Media3）
    implementation("androidx.media3:media3-exoplayer:1.9.0")

    // 权限请求（Accompanist）
    implementation("com.google.accompanist:accompanist-permissions:0.37.3")

    // Lifecycle ViewModel
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // 测试依赖
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    // Debug 工具依赖
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

}