package pika.env

import pika.core.XorShift32
import java.nio.file.Files
import java.security.MessageDigest

/**
 * 관측·보상의 **체인 해시 골든**. (tasks.md P4, M2-d, plan.md §10)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Phase 1 의 골든과 무엇이 다른가
 * ─────────────────────────────────────────────────────────────────────────────
 * Phase 1 의 골든 값은 **JS 오라클**이 만들었다. 비교할 정답이 있었기 때문이다.
 * 여기에는 정답이 없다 — 관측 설계에도 보상 설계에도 "맞는 값" 이 없다.
 * 그래서 이 골든은 **증명이 아니라 변경 감지**다. Kotlin 이 만들고 Kotlin 이 검증한다.
 *
 * 그럼에도 값이 있는 이유: 관측이 맞는지는 증명할 수 없지만 **어제의 관측과 오늘의 관측이
 * 같은지**는 증명할 수 있다. 의미론이 자리 잡은 직후에 해시를 박아 두면, 그 뒤의 모든
 * 변경에 대해 "의도한 변경인가" 를 해시가 묻는다 (plan.md §1).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ⚠️ 골든이 깨졌을 때
 * ─────────────────────────────────────────────────────────────────────────────
 * 관측 레이아웃·정규화 상수·보상 항·리셋 순서·시드 유도 중 하나가 바뀐 것이다.
 *
 *   - 의도한 변경이면 `./gradlew :engine-kotlin:env:writeEnvGolden` 으로 갱신하고
 *     **무엇을 왜 바꿨는지 커밋 메시지에 적는다.** 이것이 규칙이다.
 *   - 의도하지 않았으면 원인을 찾는다. 갱신은 해결이 아니다.
 *
 * Phase 1 과 달리 여기서는 갱신이 정당할 때가 있다 (설계가 확정 전이다).
 * 그래서 "이유를 적는 것" 이 유일한 방어선이 된다.
 */
object EnvGolden {

    /** 행동 스트림을 물리 스트림에서 갈라내는 소금. conformance 의 `INPUT_SEED_SALT` 와 같은 값. */
    const val ACTION_SALT: Int = -1640531527 // 0x9E3779B9

    /**
     * 고정 행동 시퀀스.
     *
     * ⚠️ **환경마다 독립된 RNG 를 쓴다.** 하나의 스트림에서 순서대로 퍼 가면 벡터 크기가
     *    바뀌는 순간 환경 0 이 받는 행동이 달라져서, "벡터 크기를 바꿔도 환경 0 의 수열이
     *    같다"(M2-d) 를 시험할 수 없게 된다. 시험 도구가 시험 대상을 오염시키면 안 된다.
     */
    class ActionSequence(actionSeed: Int, val numEnvs: Int, val slotCount: Int) {
        private val rngs = Array(numEnvs) { XorShift32(PikaEnv.deriveSeed(actionSeed xor ACTION_SALT, it, 0)) }
        val buffer = ByteArray(numEnvs * slotCount)

        fun next(): ByteArray {
            for (i in 0 until numEnvs) {
                val rng = rngs[i]
                for (k in 0 until slotCount) {
                    buffer[i * slotCount + k] = (rng.nextRand() % ActionCodec.ACTION_COUNT).toByte()
                }
            }
            return buffer
        }
    }

    /** 골든 케이스 하나. 이름이 곧 골든 파일의 키다. */
    data class Case(
        val name: String,
        val config: EnvConfig,
        val numEnvs: Int,
        val frames: Int,
        val actionSeed: Int = 1,
    )

    /**
     * 케이스 표.
     *
     * 플래그마다 한 줄씩 둔다 — 플래그를 끄고 켜는 코드 경로도 회귀의 대상이기 때문이다.
     * 총 env-step 은 10만 미만이라 `./gradlew build` 안에서 돌릴 수 있다.
     */
    val CASES: List<Case> by lazy {
        listOf(
            Case("trackA", EnvConfig(slots = Slots.EXTERNAL_VS_FSM), numEnvs = 4, frames = 2000),
            Case("trackA-right", EnvConfig(slots = Slots.FSM_VS_EXTERNAL), numEnvs = 4, frames = 2000),
            Case("trackB", EnvConfig(slots = Slots.EXTERNAL_VS_EXTERNAL), numEnvs = 4, frames = 2000),
            // ⚠️ 미러링은 **오른쪽 슬롯에만** 걸린다. 기본 구성(External vs Fsm) 은 외부 슬롯이
            //    왼쪽 하나뿐이라 미러링을 꺼도 관측이 한 비트도 안 바뀐다 — 아무것도 시험하지
            //    못하는 케이스가 된다. 그래서 오른쪽이 외부인 구성으로 둔다.
            Case(
                "no-mirror",
                EnvConfig(slots = Slots.EXTERNAL_VS_EXTERNAL, mirrorObservations = false),
                numEnvs = 2, frames = 1000,
            ),
            Case(
                "no-landing",
                EnvConfig(obs = ObsSpec.Options(includeExpectedLanding = false)),
                numEnvs = 2, frames = 1000,
            ),
            Case(
                "side-flag",
                EnvConfig(obs = ObsSpec.Options(includeSideFlag = true)),
                numEnvs = 2, frames = 1000,
            ),
            Case("no-edge-trigger", EnvConfig(edgeTriggerPowerHit = false), numEnvs = 2, frames = 1000),
            // truncation 경로를 반드시 밟게 한다. 짧은 랠리 상한은 이 케이스에서만 쓴다.
            Case("truncating", EnvConfig(maxRallyFrames = 64), numEnvs = 2, frames = 1000),
            Case(
                "shaped",
                EnvConfig(
                    slots = Slots.EXTERNAL_VS_EXTERNAL,
                    rewardWeights = RewardWeights(
                        rallyWin = 1f, ballTouch = 0.1f, crossedNet = 0.05f,
                        opponentMiss = 0.2f, timePenalty = 0.001f,
                    ),
                ),
                numEnvs = 2, frames = 1000,
            ),
            Case("seed-7", EnvConfig(baseSeed = 7), numEnvs = 1, frames = 1000),
            Case("wide", EnvConfig(), numEnvs = 16, frames = 500),
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 체인 해시
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * `h_n = SHA256(h_{n-1} ‖ obs_n ‖ reward_n ‖ flags_n)` — Phase 1 과 같은 규약.
     *
     * 프레임별 해시를 쓰지 않는 이유도 같다: 위치를 짚어 주지만 커밋할 수 없는 양이 된다.
     * 여기서 빨간불이 뜨면 로컬에서 케이스를 좁혀 가며 찾으면 된다.
     */
    fun chainFor(case: Case): String {
        val vec = VectorEnv(case.config, case.numEnvs)
        vec.reset()
        val actions = ActionSequence(case.actionSeed, case.numEnvs, vec.slotCount)

        val digest = MessageDigest.getInstance("SHA-256")
        var chain = ByteArray(32)
        val obsBytes = ByteArray(vec.observations.size * 4)
        val rewardBytes = ByteArray(vec.rewards.size * 4)

        repeat(case.frames) {
            vec.step(actions.next())
            packFloats(vec.observations, obsBytes)
            packFloats(vec.rewards, rewardBytes)
            digest.reset()
            digest.update(chain)
            digest.update(obsBytes)
            digest.update(rewardBytes)
            digest.update(vec.terminated)
            digest.update(vec.truncated)
            chain = digest.digest()
        }
        return toHex(chain)
    }

    /**
     * float32 little-endian 으로 이어붙인다.
     *
     * ⚠️ `toRawBits` 를 쓴다. NaN 의 비트 패턴까지 그대로 간다 — 정규화 상수를 잘못 바꿔
     *    0으로 나누는 사고가 나면 해시가 그것을 잡아야 한다.
     */
    fun packFloats(values: FloatArray, out: ByteArray) {
        require(out.size == values.size * 4)
        var j = 0
        for (v in values) {
            val bits = v.toRawBits()
            out[j++] = (bits and 0xFF).toByte()
            out[j++] = ((bits ushr 8) and 0xFF).toByte()
            out[j++] = ((bits ushr 16) and 0xFF).toByte()
            out[j++] = ((bits ushr 24) and 0xFF).toByte()
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()

    fun toHex(bytes: ByteArray, count: Int = bytes.size): String {
        val sb = StringBuilder(count * 2)
        for (i in 0 until count) {
            val v = bytes[i].toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 파일
    // ─────────────────────────────────────────────────────────────────────────

    private const val HEADER = """# 관측·보상 골든 체인 해시 — 회귀 감지용. (tasks.md P4 / M2-d)
#
# 한 줄 = 케이스 하나:  <케이스> <환경수> <프레임수> <레이아웃해시16> <체인해시(SHA-256 64자)>
# 케이스의 구성(슬롯·플래그·가중치)은 EnvGolden.CASES 가 정의한다. 이 파일은 결과만 담는다.
#
# 재생성:  ./gradlew :engine-kotlin:env:writeEnvGolden
#
# ⚠️ 깨졌다고 그냥 재생성하지 말 것. 관측 레이아웃·정규화 상수·보상 항·리셋 순서·시드 유도
#    중 하나가 바뀐 것이다. 의도한 변경이면 갱신하되 **무엇을 왜 바꿨는지 커밋 메시지에 적는다.**
#    이 규칙이 유일한 방어선이다 — Phase 1 과 달리 여기서는 갱신이 정당할 때가 있기 때문이다."""

    data class Entry(
        val name: String,
        val numEnvs: Int,
        val frames: Int,
        val layoutHash16: String,
        val chainHex: String,
    )

    fun exists(): Boolean = Files.exists(EnvPaths.goldenEnvChain)

    fun load(): List<Entry> = parse(EnvPaths.goldenEnvChain.toFile().readText())

    internal fun parse(text: String): List<Entry> = buildList {
        for ((i, raw) in text.split("\n").withIndex()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("""\s+"""))
            require(parts.size == 5) { "${i + 1}행: 5개 항목이어야 합니다" }
            require(parts[4].matches(Regex("""^[0-9a-f]{64}$"""))) { "${i + 1}행: 체인 해시는 hex 64자" }
            add(Entry(parts[0], parts[1].toInt(), parts[2].toInt(), parts[3], parts[4]))
        }
    }

    internal fun render(entries: List<Entry>): String = buildString {
        appendLine(HEADER)
        for (e in entries) appendLine("${e.name} ${e.numEnvs} ${e.frames} ${e.layoutHash16} ${e.chainHex}")
    }

    fun compute(case: Case): Entry =
        Entry(case.name, case.numEnvs, case.frames, case.config.layoutHash.take(16), chainFor(case))

    fun write(onProgress: (String) -> Unit = {}): List<Entry> {
        val entries = CASES.map {
            onProgress("${it.name} (N=${it.numEnvs}, ${it.frames}프레임)")
            compute(it)
        }
        Files.createDirectories(EnvPaths.goldenEnvChain.parent)
        EnvPaths.goldenEnvChain.toFile().writeText(render(entries))
        return entries
    }

    /** 골든과 어긋난 케이스 하나. */
    data class Failure(val entry: Entry, val actual: Entry)

    fun verify(entries: List<Entry> = load()): List<Failure> {
        val byName = CASES.associateBy { it.name }
        return entries.mapNotNull { e ->
            val case = byName[e.name] ?: error("골든에 있는 케이스 '${e.name}' 가 CASES 에 없습니다")
            check(case.numEnvs == e.numEnvs && case.frames == e.frames) {
                "케이스 '${e.name}' 의 규모가 바뀌었습니다 (골든 ${e.numEnvs}×${e.frames}, " +
                    "현재 ${case.numEnvs}×${case.frames}). 의도한 변경이면 골든을 갱신하세요."
            }
            val actual = compute(case)
            if (actual.chainHex == e.chainHex && actual.layoutHash16 == e.layoutHash16) null else Failure(e, actual)
        }
    }
}

/** `./gradlew :engine-kotlin:env:writeEnvGolden` 의 진입점. */
object GoldenMain {
    @JvmStatic
    fun main(args: Array<String>) {
        println("골든 재생성: ${EnvPaths.goldenEnvChain}")
        val entries = EnvGolden.write { println("  $it") }
        println("케이스 ${entries.size}개를 썼습니다.")
        println("⚠️ 무엇을 왜 바꿨는지 커밋 메시지에 적으세요 (plan.md §10).")
    }
}
