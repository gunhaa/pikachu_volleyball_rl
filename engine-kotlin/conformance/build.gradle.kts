plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":engine-kotlin:core"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // 차분 테스트는 Node 자식 프로세스를 띄운다. 저장소 루트를 기준으로 경로를 잡는다.
    systemProperty("repo.root", rootProject.projectDir.absolutePath)
    testLogging { showStandardStreams = true }
}

/**
 * 전수 차분 검사. `build` 에는 포함하지 않는다 — Node 와 upstream/ 이 필요하고
 * 분 단위로 걸리기 때문이다. CI 는 축소 샘플 골든 회귀만 본다. (NFR-2)
 */
val conformance = tasks.register<JavaExec>("conformance") {
    group = "verification"
    description = "JS 오라클과 프레임별 상태 해시를 전수 대조한다 (plan.md §6.3)."
    mainClass.set("pika.conformance.Main")
    classpath = sourceSets["main"].runtimeClasspath
    systemProperty("repo.root", rootProject.projectDir.absolutePath)
}
