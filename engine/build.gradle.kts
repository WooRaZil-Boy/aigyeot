import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 판단 코어: 안드로이드·실제 시계·파일 입출력 없이 JVM에서 시험한다.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    api(libs.kotlinx.serialization.json) // SendToPhone body(JsonObject)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

// 판단 코어는 규칙 전체가 순수 함수라 줄 커버리지 하한을 건다(-Pcoverage :engine:koverVerify).
kover { reports { verify { rule { minBound(95) } } } }
