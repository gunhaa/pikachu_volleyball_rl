package pika.conformance

import java.nio.file.Files

/**
 * CI 골든 회귀 — 축소 샘플의 **체인 해시**를 저장소에 커밋해 두고 Node 없이 대조한다. (NFR-2)
 *
 * 왜 체인 해시인가 (plan.md §5.4)
 *   프레임별 해시는 불일치 위치를 즉시 알려주지만 에피소드당 수백 줄이라 커밋할 수 없다.
 *   체인 해시 `h_n = SHA256(h_{n-1} ‖ state_n)` 는 에피소드당 32바이트로 **저장에 적합**하다.
 *   위치를 못 짚는 건 문제가 되지 않는다 — 여기서 빨간불이 뜨면 로컬에서 전수 차분을 돌리면 된다.
 *
 * 왜 Node 없이 돌려야 하는가
 *   CI 에서 골든을 확인하려면 `upstream/` 클론과 Node 실행이 필요하고, 그러면
 *   "업스트림 저장소가 사라지면 CI 가 죽는" 의존이 생긴다. 골든 값은 **JS 오라클이 만들지만**
 *   검증은 Kotlin 혼자 한다.
 *
 * ⚠️ 골든이 깨졌을 때 골든 파일을 다시 생성해서 초록으로 만드는 것은 **검증을 무력화하는 것**이다.
 *    (plan.md §5.0) 재생성은 State Spec 이나 하네스를 **의도적으로** 바꿨을 때만이다.
 */
object Golden {

    /** 골든 표본 — plan.md §8 "축소 샘플(약 200 시드)". */
    val SAMPLE: List<Sample> by lazy {
        listOf(
            Sample(Generator.UNIFORM, "1..200", 600),
            Sample(Generator.BIASED, "1..200", 600),
            Sample(Generator.FSM, "1..200", 600),
            Sample(Generator.TARGETED, TargetedCases.allSeedSpec(), 0),
        )
    }

    data class Sample(val gen: Generator, val seeds: String, val frames: Int)

    /** 한 에피소드의 골든 값. */
    data class Entry(val gen: Generator, val frames: Int, val seed: Int, val chainHex: String)

    private const val HEADER = """# 골든 체인 해시 — CI 회귀용. (tasks.md P8 / NFR-2)
#
# 값을 만든 쪽은 JS 오라클이고, 검증하는 쪽은 Kotlin 혼자다 (Node 불필요).
# 한 줄 = 에피소드 하나:  <생성기> <프레임수> <시드> <체인해시(SHA-256 64자)>
# 프레임수 0 은 표적 케이스 — 프레임 수를 케이스가 정한다는 뜻이다.
#
# 재생성:  ./gradlew conformance --args="--write-golden"
# ⚠️ 깨졌다고 재생성하지 말 것. 먼저 `./gradlew conformance` 로 원인을 찾는다."""

    // ─────────────────────────────────────────────────────────────────────────
    // 읽기 / 쓰기
    // ─────────────────────────────────────────────────────────────────────────

    fun exists(): Boolean = Files.exists(RepoPaths.goldenChainHashes)

    fun load(): List<Entry> = parse(RepoPaths.goldenChainHashes.toFile().readText())

    internal fun parse(text: String): List<Entry> = buildList {
        for ((i, raw) in text.split("\n").withIndex()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("""\s+"""))
            require(parts.size == 4) { "${i + 1}행: '<생성기> <프레임수> <시드> <체인해시>' 4개 항목" }
            require(parts[3].matches(Regex("""^[0-9a-f]{64}$"""))) { "${i + 1}행: 체인 해시는 hex 64자" }
            add(Entry(Generator.of(parts[0]), parts[1].toInt(), parts[2].toInt(), parts[3]))
        }
    }

    internal fun render(entries: List<Entry>): String = buildString {
        appendLine(HEADER)
        for (e in entries) appendLine("${e.gen.cliName} ${e.frames} ${e.seed} ${e.chainHex}")
    }

    /**
     * JS 오라클에서 골든 값을 새로 받아 파일에 쓴다.
     *
     * **Kotlin 이 계산한 값을 쓰지 않는다.** 그러면 골든이 "구현이 스스로를 증명하는" 동어반복이 된다.
     */
    fun write(onProgress: (String) -> Unit = {}): List<Entry> {
        val entries = mutableListOf<Entry>()
        for (sample in SAMPLE) {
            onProgress("${sample.gen.cliName} ${sample.seeds}")
            val seeds = Lockstep.parseSeeds(sample.seeds)
            JsOracle.start(sample.seeds, sample.frames, sample.gen, mode = "chain-hash").use { oracle ->
                for (seed in seeds) {
                    val marker = oracle.readLine()
                        ?: run { oracle.assertHealthy("골든 생성 중"); error("오라클 출력이 일찍 끝났습니다") }
                    check(marker == "E $seed") { "에피소드 마커가 어긋났습니다: '$marker'" }
                    val chain = oracle.readLine() ?: error("체인 해시 줄이 없습니다 (seed=$seed)")
                    check(chain.startsWith("C ")) { "체인 해시 줄이 아닙니다: '$chain'" }
                    entries += Entry(sample.gen, sample.frames, seed, chain.drop(2))
                }
            }
        }
        Files.createDirectories(RepoPaths.goldenChainHashes.parent)
        RepoPaths.goldenChainHashes.toFile().writeText(render(entries))
        return entries
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 검증 (Node 불필요)
    // ─────────────────────────────────────────────────────────────────────────

    /** 골든과 어긋난 에피소드 하나. */
    data class Failure(val entry: Entry, val actualHex: String)

    /**
     * Kotlin 엔진만으로 체인 해시를 다시 계산해 골든과 대조한다.
     *
     * @return 어긋난 에피소드 목록 (비어 있으면 통과)
     */
    fun verify(entries: List<Entry> = load()): List<Failure> {
        val ints = IntArray(StateSpec.BASE_INT_COUNT)
        val bytes = ByteArray(StateSpec.BASE_INT_COUNT * 4)
        val digest = StateSpec.sha256()

        return entries.mapNotNull { e ->
            var chain = StateSpec.chainSeed()
            val step = { physics: pika.core.PikaPhysics, touching: Boolean ->
                StateSpec.writeInts(physics, touching, strict = false, out = ints)
                StateSpec.packInts(ints, bytes)
                chain = StateSpec.chainStep(digest, chain, bytes)
            }
            if (e.gen == Generator.TARGETED) {
                Harness.runTargetedCase(e.seed) { p, t, _, _ -> step(p, t) }
            } else {
                Harness.runEpisode(e.seed, e.frames, e.gen) { p, t, _, _ -> step(p, t) }
            }
            val actual = StateSpec.toHex(chain)
            if (actual == e.chainHex) null else Failure(e, actual)
        }
    }
}
