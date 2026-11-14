import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 차·폰 공용 메시지 형식. JSON 예제는 src/test/resources/protocol/에 두고 Kotlin·Swift·TypeScript가 함께 읽는다.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
