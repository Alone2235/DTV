plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")   // ★ Compose
}

android {
    namespace = "com.example.dtvremote"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.dtvremote"
        minSdk = 21
        targetSdk = 34
        versionCode = 28
        versionName = "3.04"
    }
    signingConfigs {
        create("dtv") {
            storeFile = file("dtv.keystore")
            storePassword = "dtv2019"
            keyAlias = "dtv"
            keyPassword = "dtv2019"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("dtv") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("dtv")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    buildFeatures { compose = true }            // ★ 开启 Compose
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:3.12.13")
    // ★ iOS26 液态玻璃（View 版；Android 13+ 生效）
    implementation("com.qmdeve.liquidglass:core:1.1.0")
    // ★ Jetpack Compose（成熟方案：HorizontalPager / spring 动画 / Material3 组件）
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
}

// ★ 关键：把 androidx 全部钉到同一套现代版本。
//   2.08 崩在类加载阶段的根因就是 viewpager2:1.0.0 把 core:1.1.0 / recyclerview:1.1.0
//   这类 2019 老版本拖进来，和液态玻璃库要的新版 androidx 混用 → 运行时链接错误
//   （NoSuchMethodError/AbstractMethodError），连 Application 崩溃处理器都来不及装。
configurations.all {
    resolutionStrategy {
        force(
            "androidx.core:core:1.13.1",
            "androidx.core:core-ktx:1.13.1",
            "androidx.annotation:annotation:1.8.0",
            "androidx.collection:collection:1.4.2",
            "androidx.recyclerview:recyclerview:1.3.2",
            "androidx.customview:customview:1.1.0",
            "androidx.lifecycle:lifecycle-runtime:2.8.4",
            "androidx.lifecycle:lifecycle-common:2.8.4",
            "androidx.fragment:fragment:1.8.2",
            "androidx.activity:activity:1.9.1"
        )
    }
}
