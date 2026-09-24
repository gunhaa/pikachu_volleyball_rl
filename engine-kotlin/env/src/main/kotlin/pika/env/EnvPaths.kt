package pika.env

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** 저장소 안의 고정 경로. Gradle 이 `repo.root` 시스템 프로퍼티로 알려준다. */
object EnvPaths {
    val root: Path by lazy {
        System.getProperty("repo.root")?.let { return@lazy Paths.get(it) }
        var p: Path? = Paths.get("").toAbsolutePath()
        while (p != null && !Files.exists(p.resolve("settings.gradle.kts"))) p = p.parent
        requireNotNull(p) { "저장소 루트를 찾지 못했습니다. -Drepo.root=<경로> 로 지정하세요." }
    }

    /** 관측 레이아웃의 단일 정의 (NFR-5). */
    val obsSpecProto: Path get() = root.resolve("proto/obs_spec.proto")

    /** 관측·보상 체인 해시 골든 (P4). */
    val goldenEnvChain: Path get() = root.resolve("engine-kotlin/env/golden/env-chain-hashes.txt")

    /** 결정 관측 골든 — 체인 + 리플레이 (Phase 5 P1). JS `ObsEncoder` 가 이것과 대조한다. */
    val goldenObsDir: Path get() = root.resolve("engine-kotlin/env/golden/obs")
}
