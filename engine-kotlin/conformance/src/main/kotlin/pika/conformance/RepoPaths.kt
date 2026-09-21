package pika.conformance

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** 저장소 안의 고정 경로들. Gradle 이 `repo.root` 시스템 프로퍼티로 알려준다. */
object RepoPaths {
    val root: Path by lazy {
        System.getProperty("repo.root")?.let { return@lazy Paths.get(it) }
        // Gradle 밖에서 실행된 경우: settings.gradle.kts 가 보일 때까지 올라간다.
        var p: Path? = Paths.get("").toAbsolutePath()
        while (p != null && !Files.exists(p.resolve("settings.gradle.kts"))) p = p.parent
        requireNotNull(p) { "저장소 루트를 찾지 못했습니다. -Drepo.root=<경로> 로 지정하세요." }
    }

    val stateSpecProto: Path get() = root.resolve("proto/state_spec.proto")
    val oracleDir: Path get() = root.resolve("tools/js-oracle")
    val oracleScript: Path get() = oracleDir.resolve("run.mjs")
    val upstreamPhysics: Path get() = root.resolve("upstream/src/resources/js/physics.js")

    /** 표적 케이스 표. JS 오라클과 **같은 파일**을 읽는다 (plan.md §6.4). */
    val targetedCases: Path get() = root.resolve("tools/targeted-cases.txt")

    /** CI 골든 회귀용 체인 해시. 커밋 대상이다 (NFR-2). */
    val goldenChainHashes: Path get() = root.resolve("engine-kotlin/conformance/golden/chain-hashes.txt")

    /** 업스트림이 받아져 있는가. 없으면 오라클을 돌릴 수 없다. */
    fun upstreamIsPresent(): Boolean = Files.exists(upstreamPhysics)
}
