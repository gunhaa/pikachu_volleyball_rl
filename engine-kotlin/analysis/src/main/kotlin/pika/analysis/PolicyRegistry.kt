package pika.analysis

import java.nio.file.Files
import java.nio.file.Path

/**
 * ONNX 정책 레지스트리 — `export_onnx.py` 가 쓰는 `runs/policies/registry.jsonl` 을 읽는다. (Phase 5 FR-11, FR-12)
 *
 * ONNX SHA-256 → (체크포인트 SHA-256, label, 파일). 서버는 이것으로
 *   - 뷰어에 정책 목록과 ONNX 바이트를 주고 (`GET /api/policies`),
 *   - 라이브 제출의 `policy:<onnx sha>` 주장을 **체크포인트 SHA** 참가자로 바꾼다 — 평가 기준선과 같은 행.
 *
 * ONNX 파일은 레지스트리 경로의 **파일 이름**으로 [dir] 안에서 찾는다 (레지스트리 경로는 저장소 루트 기준이라
 * serve 를 어디서 띄우든 같게). 읽을 때 파일 SHA 를 다시 계산해 레지스트리와 다르면 실패한다 — 다른 가중치를
 * 그 이름으로 내주는 일을 막는다.
 */
class PolicyRegistry private constructor(val entries: List<Entry>) {

    class Entry(
        val label: String,
        val checkpointSha256: String,
        val onnxSha256: String,
        val obsDim: Int,
        val obsLayoutHash: String,
        val file: Path,
    ) {
        /** 적재할 참가자 — 체크포인트 SHA 로 (FR-12). */
        val participant: Ingest.Participant get() = Ingest.Participant("external", "sha256:$checkpointSha256", label)
    }

    private val byOnnx: Map<String, Entry> = entries.associateBy { it.onnxSha256 }

    fun byOnnxSha(sha: String): Entry? = byOnnx[sha]

    companion object {
        val EMPTY = PolicyRegistry(emptyList())

        private val HEX64 = Regex("[0-9a-f]{64}")

        /**
         * label 은 한 줄씩이다 — export 가 같은 label 을 덧붙이지 않는다 (plan.md §5.4). 두 줄이면 실패.
         * 파일이 없는 줄도 실패다 — 목록에 있는데 받을 수 없는 정책을 내놓지 않는다.
         */
        fun load(dir: Path): PolicyRegistry {
            val file = dir.resolve("registry.jsonl")
            require(Files.exists(file)) { "$file 가 없습니다" }
            val byLabel = LinkedHashMap<String, Entry>()
            for ((n, line) in Files.readAllLines(file).withIndex()) {
                if (line.isBlank()) continue
                val m = Json.parse(line).obj()
                val e = Entry(
                    label = m.str("label"),
                    checkpointSha256 = m.str("checkpoint_sha256"),
                    onnxSha256 = m.str("onnx_sha256"),
                    obsDim = m.int("obs_dim"),
                    obsLayoutHash = m.str("obs_layout_hash"),
                    file = dir.resolve(Path.of(m.str("onnx")).fileName),
                )
                require(HEX64.matches(e.checkpointSha256) && HEX64.matches(e.onnxSha256)) { "$file:${n + 1}: SHA-256 형식이 아닙니다" }
                require(byLabel.put(e.label, e) == null) { "$file:${n + 1}: label ${e.label} 이 두 줄입니다" }
            }
            for (e in byLabel.values) {
                require(Files.exists(e.file)) { "${e.label}: ONNX 파일이 없습니다 — ${e.file}" }
                val actual = Ingest.sha256Hex(Files.readAllBytes(e.file))
                require(actual == e.onnxSha256) { "${e.label}: ${e.file} 의 SHA ${actual.take(16)} ≠ 레지스트리 ${e.onnxSha256.take(16)}" }
            }
            val dup = byLabel.values.groupBy { it.onnxSha256 }.filterValues { it.size > 1 }
            require(dup.isEmpty()) { "서로 다른 label 이 같은 ONNX 를 가리킵니다: ${dup.values.map { l -> l.map { it.label } }}" }
            return PolicyRegistry(byLabel.values.toList())
        }
    }
}
