// Top-level build file where you can add configuration options common to all sub-projects/modules.
// Kotlin 编译器版本 2.3.20 由 kotlin-compose 插件传递依赖自动提供，
// AGP 9.0 内置 Kotlin 会使用 classpath 上已有的 Kotlin 编译器版本。

plugins {
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
}
