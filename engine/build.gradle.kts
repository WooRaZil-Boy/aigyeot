import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 판단 코어: 안드로이드·실제 시계·파일 입출력 없이 JVM에서 시험한다(구현 계획 2장).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    api(libs.kotlinx.serialization.json) // SendToPhone body(JsonObject)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
