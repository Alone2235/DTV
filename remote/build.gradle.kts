plugins {
    id("com.android.application") version "8.9.0" apply false
    // ★ 2.2.10：液态玻璃库（com.qmdeve.liquidglass:core:1.1.0）的 Kotlin 元数据是 2.2，
    //   用 2.0.21 编译会报 "binary version of its metadata is 2.2.0, expected 2.0.0"
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    // ★ Compose 编译器插件（Kotlin 2.x 起随 Kotlin 版本走）
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
