plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

// 모든 모듈은 JVM 21 을 타겟으로 고정한다.
// Gradle 을 실행하는 JDK 가 무엇이든 산출 바이트코드는 21 이다. (재현성)
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(libs.versions.jvm.get().toInt())
        }
        repositories { mavenCentral() }
    }
}
