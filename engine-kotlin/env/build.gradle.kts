plugins {
    alias(libs.plugins.kotlin.jvm)
}

/**
 * NFR-2: `env` 는 gRPC 를 모른다.
 *
 * 서버 없이 JVM in-process 로 벤치(M2-b)와 테스트를 돌릴 수 있어야 한다.
 * 그래야 종단 처리량(M2-a)과의 **뺄셈**이 RPC 계층의 비용이 된다 (plan.md §11).
 * 합쳐 두면 그 뺄셈이 불가능해지고, 미달일 때 어디를 고쳐야 할지 알 수 없다.
 */
dependencies {
    implementation(project(":engine-kotlin:core"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // 골든 파일은 저장소 경로에 있다 (P4).
    systemProperty("repo.root", rootProject.projectDir.absolutePath)
    testLogging { showStandardStreams = true }
}

// NFR-2 를 사람이 지키는 규칙이 아니라 빌드가 강제하는 규칙으로 만든다.
// (core 의 checkNoDependencies 와 같은 장치다.)
val forbidden = listOf("io.grpc", "com.google.protobuf", "io.netty")

val checkNoGrpc = tasks.register("checkNoGrpc") {
    group = "verification"
    description = "env 의 런타임 클래스패스에 gRPC·protobuf 가 없는지 확인한다 (NFR-2)."

    val banned = forbidden
    val leaked = configurations.named("runtimeClasspath").map { conf ->
        conf.incoming.resolutionResult.allDependencies
            .mapNotNull { (it as? org.gradle.api.artifacts.result.ResolvedDependencyResult)?.selected?.moduleVersion }
            .map { "${it.group}:${it.name}" }
            .distinct()
            .filter { id -> banned.any { id.startsWith(it) } }
    }

    doLast {
        check(leaked.get().isEmpty()) {
            "env 는 gRPC 를 몰라야 합니다 (NFR-2). 발견된 의존성: ${leaked.get()}\n" +
                "직렬화·RPC 는 :engine-kotlin:server 의 일입니다."
        }
    }
}

tasks.named("check") { dependsOn(checkNoGrpc) }
