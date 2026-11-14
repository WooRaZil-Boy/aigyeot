// Kotlin 플러그인을 여기서 선언해 AGP 내장 Kotlin도 같은 버전(libs의 kotlin)을 쓰게 한다.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.kover) apply false
}

// 시험 커버리지(Kover): 평소 시험은 계측 없이 돌고, -Pcoverage를 줄 때만 잰다.
//   ./gradlew -Pcoverage :engine:koverVerify          판단 코어의 줄 커버리지 하한 확인
//   ./gradlew -Pcoverage :app:koverHtmlReportDebug    모듈별 보고서(build/reports/kover)
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlinx.kover") {
        extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
            currentProject { instrumentation { disabledForAll.set(!providers.gradleProperty("coverage").isPresent) } }
            reports {
                filters { excludes { classes("*_Impl", "*_Impl\$*", "*.BuildConfig", "*ComposableSingletons*") } }   // Room·Compose가 만든 코드
            }
        }
    }
}
