plugins {
    alias(libs.plugins.kotlin.jvm)
}

// NFR-1: core 는 외부 의존성 0.
// 테스트 프레임워크조차 여기에 두지 않는다. 모든 검증은 :engine-kotlin:conformance 가 한다.
dependencies {
    // 의도적으로 비어 있음
}

/**
 * NFR-1 에서 말하는 "외부 의존성" 에 해당하지 않는 것들.
 *
 * kotlin-stdlib 는 라이브러리가 아니라 언어 런타임이다. JDK 와 같은 지위이므로
 * 이것까지 금지하면 `kotlin.math.abs` 조차 쓸 수 없다.
 * annotations 는 stdlib 이 끌고 오는 전이 의존성이다.
 */
val languageRuntime = setOf(
    "org.jetbrains.kotlin:kotlin-stdlib",
    "org.jetbrains:annotations",
)

// NFR-1 을 사람이 지키는 규칙이 아니라 빌드가 강제하는 규칙으로 만든다.
val checkNoDependencies = tasks.register("checkNoDependencies") {
    group = "verification"
    description = "core 의 런타임 클래스패스에 언어 런타임 외의 의존성이 없는지 확인한다 (NFR-1)."

    // configuration cache 호환: 실행 시점이 아니라 설정 시점에 값을 잡아둔다.
    val allowed = languageRuntime
    val leaked = configurations.named("runtimeClasspath").map { conf ->
        conf.incoming.resolutionResult.allDependencies
            .mapNotNull { (it as? org.gradle.api.artifacts.result.ResolvedDependencyResult)?.selected?.moduleVersion }
            .map { "${it.group}:${it.name}" }
            .distinct()
            .filterNot { it in allowed }
    }

    doLast {
        check(leaked.get().isEmpty()) {
            "core 는 외부 의존성이 0 이어야 합니다 (NFR-1). 발견된 의존성: ${leaked.get()}"
        }
    }
}

tasks.named("check") { dependsOn(checkNoDependencies) }
