plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("pika.analysis.Main")
}

/**
 * 분석 도구 (Phase 4). 리플레이 골든 · 적재 · 통계 · 뷰어 API.
 *
 * DB · HTTP 는 **여기에만** 둔다 (NFR-2). `env` 는 여전히 외부 의존성 0 이다.
 * 체인 해시는 `conformance` 의 `StateSpec` 을 쓴다 — Phase 1 과 같은 규약이라 새 해시를 발명하지 않는다.
 */
dependencies {
    implementation(project(":engine-kotlin:env"))
    implementation(project(":engine-kotlin:core"))
    implementation(project(":engine-kotlin:conformance"))
    // DB 는 여기에만 (NFR-2). runtimeOnly — 코드는 java.sql 만 안다.
    runtimeOnly(libs.mysql.connector)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // 골든 리플레이 · viewer-web 테스트가 저장소 경로에 있다.
    systemProperty("repo.root", rootProject.projectDir.absolutePath)
    testLogging { showStandardStreams = true }
}

tasks.named<JavaExec>("run") {
    systemProperty("repo.root", rootProject.projectDir.absolutePath)
    workingDir = rootProject.projectDir
}
