// Kotlin 플러그인을 여기서 선언해 AGP 내장 Kotlin도 같은 버전(libs의 kotlin)을 쓰게 한다.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
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

// Kotlin 린트(ktlint): 규칙은 .editorconfig에 있다. 검사는 ./gradlew ktlintCheck, 고치기는 ./gradlew ktlintFormat.
val ktlintEngine = libs.versions.ktlintEngine.get()
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(ktlintEngine)
    }
}

// 정적 분석(detekt)은 참고용이다. 기본 규칙 위에 config/detekt/detekt.yml을 얹고, `./gradlew detektAll`로 돌린다(시험 코드는 뺀다). CI와 tools/check.sh의 검사에는 넣지 않았다.
// 까닭: 안정판 1.23.x가 이 빌드의 JDK를 지원하지 않아 알파판(2.0.0-alpha)을 쓰는데, 알파판은 컴파일러 플러그인이 만드는 선언(kotlinx.serialization의 serializer())을
// 해석하지 못해 그 선언을 쓰는 파일의 타입 분석이 불완전하다.
subprojects {
    apply(plugin = "dev.detekt")
    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig.set(true)
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    }
    tasks.withType<dev.detekt.gradle.Detekt>().configureEach { exclude("**/src/test/**", "**/src/androidTest/**") }
}
tasks.register("detektAll") {
    group = "verification"
    description = "모든 모듈의 detekt(타입 분석, main)"
    dependsOn(":engine:detektMain", ":shared:detektMain", ":phone:detektDebug", ":app:detektDebug")
}
