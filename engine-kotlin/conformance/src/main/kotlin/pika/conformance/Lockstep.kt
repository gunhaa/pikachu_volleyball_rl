package pika.conformance

import pika.core.PikaPhysics
import pika.core.PikaUserInput

/** 첫 불일치 지점. lockstep 이므로 탐색 없이 바로 나온다. (plan.md §5.3) */
data class Mismatch(
    val seed: Int,
    val frame: Int,
    val expected: String,
    val actual: String,
    val gen: Generator,
    val frames: Int,
    val strict: Boolean,
)

/** 한 번의 차분 실행 결과. */
data class DiffResult(val episodes: Int, val framesCompared: Long, val mismatch: Mismatch?) {
    val allMatched: Boolean get() = mismatch == null
}

/**
 * 2단 비교의 1단 — 프레임별 해시 lockstep 대조. (plan.md §5)
 *
 * Node 오라클을 자식 프로세스로 띄우고, stdout 파이프로 프레임별 해시를 받아
 * Kotlin 계산 결과와 즉시 비교한다. 두 구현이 나란히 전진한다.
 */
class Lockstep(
    private val frames: Int,
    private val gen: Generator,
    private val strict: Boolean = false,
    /**
     * true 면 엔진을 돌리지 않고 라운드 리셋만 대조한다 (`--mode reset-probe`).
     * 엔진 포팅 전에도 하네스 자체를 검증할 수 있다.
     */
    private val probeResets: Boolean = false,
) {
    private val oracleMode: String = if (probeResets) "reset-probe" else "frame-hash"

    /**
     * @param seedSpec 오라클 CLI 와 같은 표기 (`1..7000`, `42`, `1,2,3`)
     * @param onEpisode 진행 상황 콜백 (완료한 에피소드 수, 시드)
     * @return 첫 불일치 또는 전부 일치
     */
    fun run(seedSpec: String, onEpisode: (Int, Int) -> Unit = { _, _ -> }): DiffResult {
        val seeds = parseSeeds(seedSpec)
        val intCount = StateSpec.intCount(strict)
        val ints = IntArray(intCount)
        val bytes = ByteArray(intCount * 4)
        val digest = StateSpec.sha256()

        var framesCompared = 0L

        JsOracle.start(seedSpec, frames, gen, mode = oracleMode, strict = strict).use { oracle ->
            for ((index, seed) in seeds.withIndex()) {
                val marker = oracle.readLine()
                    ?: run {
                        oracle.assertHealthy("시드 $seed 의 에피소드 마커를 기다리는 중")
                        error("오라클 출력이 시드 $seed 에서 예고 없이 끝났습니다")
                    }
                check(marker == "E $seed") { "에피소드 마커가 어긋났습니다: '$marker' (기대: 'E $seed')" }

                var mismatch: Mismatch? = null
                val compare = { physics: PikaPhysics, touching: Boolean, f: Int ->
                    if (mismatch == null) {
                        val jsHash = oracle.readLine()
                            ?: run {
                                oracle.assertHealthy("시드 $seed 프레임 $f 해시를 기다리는 중")
                                error("오라클 출력이 시드 $seed 프레임 $f 에서 끝났습니다")
                            }

                        StateSpec.writeInts(physics, touching, strict, ints)
                        StateSpec.packInts(ints, bytes)
                        val ktHash = StateSpec.frameHash(digest, bytes)
                        framesCompared++

                        if (jsHash != ktHash) {
                            mismatch = Mismatch(seed, f, jsHash, ktHash, gen, frames, strict)
                        }
                    }
                }

                if (probeResets) {
                    Harness.runResetProbe(seed, frames, gen) { physics, i -> compare(physics, false, i) }
                } else {
                    Harness.runEpisode(seed, frames, gen) { physics, touching, _, f -> compare(physics, touching, f) }
                }

                if (mismatch != null) {
                    return DiffResult(index + 1, framesCompared, mismatch)
                }
                onEpisode(index + 1, seed)
            }
        }
        return DiffResult(seeds.size, framesCompared, null)
    }

    companion object {
        fun parseSeeds(text: String): List<Int> = buildList {
            for (part in text.split(",")) {
                val range = Regex("""^(\d+)\.\.(\d+)$""").find(part)
                if (range != null) {
                    val lo = range.groupValues[1].toInt()
                    val hi = range.groupValues[2].toInt()
                    require(hi >= lo) { "잘못된 시드 범위: $part" }
                    for (s in lo..hi) add(s)
                } else {
                    require(part.matches(Regex("""^\d+$"""))) { "잘못된 시드 표기: $part" }
                    add(part.toInt())
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2단 — 드릴다운
// ─────────────────────────────────────────────────────────────────────────────

/** 필드 단위 차이 하나. */
data class FieldDiff(val name: String, val js: Int, val kotlin: Int)

/** 불일치 드릴다운 결과. */
data class Drilldown(
    val mismatch: Mismatch,
    val diffs: List<FieldDiff>,
    /** 프레임 F-1 의 상태 (양쪽이 동일한 마지막 프레임). F=0 이면 null. */
    val previousState: IntArray?,
    /** 프레임 F 의 입력 */
    val input: Array<IntArray>,
) {
    fun report(): String = buildString {
        appendLine("불일치 드릴다운")
        appendLine("  seed=${mismatch.seed} frame=${mismatch.frame} gen=${mismatch.gen.cliName} strict=${mismatch.strict}")
        appendLine("  js  해시: ${mismatch.expected}")
        appendLine("  kt  해시: ${mismatch.actual}")
        appendLine()
        if (diffs.isEmpty()) {
            appendLine("  ⚠️ 해시는 다른데 필드 차이가 없습니다. 직렬화 규약이나 해시 구현을 의심하세요.")
        } else {
            appendLine("  갈라진 필드 ${diffs.size}개:")
            val w = diffs.maxOf { it.name.length }
            for (d in diffs) {
                appendLine("    ${d.name.padEnd(w)}  js=${d.js}  kt=${d.kotlin}  (차이 ${d.kotlin - d.js})")
            }
        }
        appendLine()
        appendLine("  프레임 ${mismatch.frame} 의 입력:")
        appendLine("    player1: xDirection=${input[0][0]} yDirection=${input[0][1]} powerHit=${input[0][2]}")
        appendLine("    player2: xDirection=${input[1][0]} yDirection=${input[1][1]} powerHit=${input[1][2]}")
    }

    /**
     * 최소 재현 케이스. 프레임 F-1 까지는 양쪽이 같으므로 에피소드 전체를 재현할 필요가 없다.
     * (plan.md §5.3 — "7,000 에피소드 중 1건 실패"가 시드 하나짜리 회귀 테스트로 축소된다)
     */
    fun minimalRepro(): String {
        val m = mismatch
        return """
            |@Test
            |fun `seed=${m.seed} frame=${m.frame} 회귀`() {
            |    // 프레임 ${m.frame} 에서 갈라졌다: ${diffs.joinToString(", ") { it.name }}
            |    // ${if (m.frame == 0) "첫 프레임부터 갈라졌다." else "프레임 ${m.frame - 1} 까지는 JS 와 일치했다."}
            |    val diff = Lockstep(frames = ${m.frame + 1}, gen = Generator.${m.gen.name}, strict = ${m.strict})
            |        .run("${m.seed}")
            |    assertTrue(diff.allMatched, "seed=${m.seed} frame=${m.frame} 재발")
            |}
        """.trimMargin()
    }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * 실패한 시드 하나만 `--mode full` 로 재실행해 필드 단위 차이를 뽑는다.
 *
 * **평상시엔 싸게, 문제 생겼을 때만 비싸게.** 전수 검사는 해시만 흘리고,
 * 전체 상태 덤프는 실패한 그 한 에피소드에만 쓴다. (plan.md §5.3)
 */
object Drilldowns {

    private val F_RE = Regex(""""f"\s*:\s*(\d+)""")
    private val S_RE = Regex(""""s"\s*:\s*\[([^\]]*)]""")
    private val I_RE = Regex(""""i"\s*:\s*\[\[([^\]]*)],\[([^\]]*)]]""")

    fun of(m: Mismatch): Drilldown {
        val upTo = m.frame + 1
        val jsStates = HashMap<Int, IntArray>()
        val jsInputs = HashMap<Int, Array<IntArray>>()

        JsOracle.start("${m.seed}", upTo, m.gen, mode = "full", strict = m.strict).use { oracle ->
            val marker = oracle.readLine()
            check(marker == "E ${m.seed}") { "에피소드 마커가 어긋났습니다: '$marker'" }
            repeat(upTo) {
                val line = oracle.readLine()
                    ?: run {
                        oracle.assertHealthy("드릴다운 중")
                        error("드릴다운 출력이 일찍 끝났습니다")
                    }
                val f = F_RE.find(line)?.groupValues?.get(1)?.toInt() ?: error("f 파싱 실패: $line")
                val s = S_RE.find(line)?.groupValues?.get(1) ?: error("s 파싱 실패: $line")
                val i = I_RE.find(line) ?: error("i 파싱 실패: $line")
                jsStates[f] = s.split(",").map { it.trim().toInt() }.toIntArray()
                jsInputs[f] = arrayOf(
                    i.groupValues[1].split(",").map { it.trim().toInt() }.toIntArray(),
                    i.groupValues[2].split(",").map { it.trim().toInt() }.toIntArray(),
                )
            }
        }

        // Kotlin 쪽 같은 구간을 다시 돌린다.
        val ktStates = HashMap<Int, IntArray>()
        val ints = IntArray(StateSpec.intCount(m.strict))
        Harness.runEpisode(m.seed, upTo, m.gen) { physics, touching, _, f ->
            StateSpec.writeInts(physics, touching, m.strict, ints)
            ktStates[f] = ints.copyOf()
        }

        val names = StateSpec.fieldNames(m.strict)
        val js = jsStates.getValue(m.frame)
        val kt = ktStates.getValue(m.frame)
        val diffs = names.indices
            .filter { js[it] != kt[it] }
            .map { FieldDiff(names[it], js[it], kt[it]) }

        return Drilldown(
            mismatch = m,
            diffs = diffs,
            previousState = if (m.frame > 0) jsStates[m.frame - 1] else null,
            input = jsInputs.getValue(m.frame),
        )
    }
}

/** 테스트 코드에서 쓰기 편하도록 입력 3요소를 [PikaUserInput] 으로 바꾼다. */
fun IntArray.toUserInput(): PikaUserInput = PikaUserInput().also {
    it.xDirection = this[0]
    it.yDirection = this[1]
    it.powerHit = this[2]
}
