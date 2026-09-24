package pika.env

import pika.env.replay.Replay
import pika.env.replay.ReplayCodec
import pika.env.replay.ReplayRecorder
import pika.env.replay.SeedMode
import java.nio.file.Files
import java.security.MessageDigest

/**
 * **결정 관측** 골든. (Phase 5 P1, FR-1, M5-a 의 기준, plan.md §3)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * [EnvGolden] 과 무엇이 다른가
 * ─────────────────────────────────────────────────────────────────────────────
 * [EnvGolden] 의 체인은 관측·보상·플래그를 한 해시에 섞고, autoreset 스텝까지 센다 (plan.md §2.1).
 * 브라우저에는 보상도 autoreset 도 없다. 그래서 같은 케이스 구성으로 **관측만**, 그것도
 * **정책이 행동을 정하는 순간의 관측만** 담은 체인을 따로 만든다.
 *
 * ```
 * h_0 = 0³²
 * h_n = SHA256(h_{n-1} ‖ slot(1바이트, 0 = 왼쪽) ‖ obs_n(float32 LE))   // 외부 슬롯마다, 왼쪽 → 오른쪽
 * ```
 *
 * "결정 관측" = 물리가 도는 스텝에 들어간 행동을 정한 관측. terminal 관측(랠리의 마지막)은 다음 스텝이
 * autoreset 이라 행동이 버려지므로 **들어가지 않는다.** autoreset 스텝이 내보낸 관측은 새 랠리의 첫
 * 결정 관측이다. 그래서 결정 수 = 물리 프레임 수 = 리플레이 프레임 수의 합이다.
 *
 * JS 는 `EnvConfig` 를 모른다. 경기는 **리플레이로** 넘긴다 — 환경마다 [ReplayRecorder](RALLY) 를 달고,
 * 케이스가 끝날 때 진행 중이던 게임은 상한 컷 모양으로 자른다 (§3.3).
 *
 * ⚠️ 깨졌을 때의 규칙은 [EnvGolden] 과 같다 — 의도한 변경일 때만 `writeObsGolden` 으로 갱신하고
 *    **무엇을 왜 바꿨는지 커밋 메시지에 적는다.**
 */
object ObsGolden {

    /** 벡터 크기는 필요 없다 — 환경 i 의 수열은 `(baseSeed, i, k)` 만의 함수다 (§3.2). */
    const val MAX_ENVS: Int = 2

    data class Case(
        val name: String,
        val config: EnvConfig,
        val numEnvs: Int,
        val frames: Int,
        val actionSeed: Int = 1,
    )

    /**
     * [EnvGolden.CASES] 11개의 구성을 1:1 로 가져오고, Track A 정책의 실제 구성 두 개를 더한다 (§2.2).
     *
     * `side-flag` 는 왼쪽 외부 슬롯 하나뿐이라 "미러 + 진영 플래그 +1" 을 아무도 계산하지 않았다.
     * 진영 플래그를 미러링하지 않는다는 규칙은 정확히 그 조합에서만 의미를 가진다.
     */
    val CASES: List<Case> by lazy {
        EnvGolden.CASES.map { Case(it.name, it.config, minOf(it.numEnvs, MAX_ENVS), it.frames, it.actionSeed) } +
            listOf(
                Case(
                    "side-flag-right",
                    EnvConfig(slots = Slots.FSM_VS_EXTERNAL, obs = ObsSpec.Options(includeSideFlag = true)),
                    numEnvs = 2, frames = 1000,
                ),
                Case(
                    "side-flag-both",
                    EnvConfig(slots = Slots.EXTERNAL_VS_EXTERNAL, obs = ObsSpec.Options(includeSideFlag = true)),
                    numEnvs = 2, frames = 1000,
                ),
            )
    }

    /** 환경 하나의 결과. [games] 는 파일 이름 순서의 리플레이다. */
    class EnvResult(val envIndex: Int, val games: List<Pair<String, Replay>>, val decisions: Int, val chainHex: String)

    class CaseResult(val case: Case, val envs: List<EnvResult>)

    /**
     * 결정 관측을 받는 쪽. 테스트가 체인 대신 관측 자체를 들여다볼 때 쓴다.
     * `obs` 버퍼는 재사용되므로 필요하면 복사한다.
     */
    fun interface Probe {
        fun onDecision(envIndex: Int, decision: Int, slot: Int, obs: FloatArray, offset: Int, dim: Int)
    }

    fun run(case: Case, probe: Probe? = null): CaseResult {
        val config = case.config
        val slotCount = config.slotCount
        val externalSlots = config.externalSlots
        val dim = config.obsDim
        val actions = EnvGolden.ActionSequence(case.actionSeed, case.numEnvs, slotCount)

        val games = Array(case.numEnvs) { ArrayList<Replay>() }
        // 기록기는 여기서 들고 있는다 — 케이스 끝에서 자르려고. (PikaEnv 는 NFR-1 로 손대지 않는다.)
        val recorders = Array(case.numEnvs) { i -> ReplayRecorder(SeedMode.RALLY) { games[i].add(it) } }
        val envs = Array(case.numEnvs) { i -> PikaEnv(config, i, recorders[i]) }
        val obs = Array(case.numEnvs) { FloatArray(slotCount * dim) }
        val rewards = FloatArray(slotCount)
        val terms = FloatArray(slotCount * RewardTerms.COUNT)
        for (i in envs.indices) envs[i].reset(obs[i], 0)

        val digest = MessageDigest.getInstance("SHA-256")
        val chains = Array(case.numEnvs) { ByteArray(32) }
        val decisions = IntArray(case.numEnvs)
        val obsBytes = ByteArray(dim * 4)
        val one = FloatArray(dim)

        repeat(case.frames) {
            val a = actions.next()
            for (i in envs.indices) {
                val env = envs[i]
                // autoreset 스텝이 아니면 이번 스텝에 물리가 돈다 — 들고 있던 관측이 그 행동을 정했다.
                if (!env.pendingReset) {
                    for (k in 0 until slotCount) {
                        System.arraycopy(obs[i], k * dim, one, 0, dim)
                        EnvGolden.packFloats(one, obsBytes)
                        digest.reset()
                        digest.update(chains[i])
                        digest.update(externalSlots[k].toByte())
                        digest.update(obsBytes)
                        chains[i] = digest.digest()
                        probe?.onDecision(i, decisions[i], externalSlots[k], obs[i], k * dim, dim)
                    }
                    decisions[i]++
                }
                env.step(a, i * slotCount, obs[i], 0, rewards, 0, terms, 0)
            }
        }

        // 케이스 끝 — 진행 중인 게임을 자른다. 게임이 방금 끝났으면 이미 내보냈다.
        for (i in envs.indices) {
            val env = envs[i]
            if (env.pendingReset && !env.game.gameEnded) {
                // 랠리가 막 끝났다. 다음 랠리를 열어(시드 기록) 0프레임 미완 랠리로 자른다.
                // 이 스텝은 물리가 돌지 않으므로 결정도 프레임도 생기지 않는다.
                env.step(ByteArray(slotCount), 0, obs[i], 0, rewards, 0, terms, 0)
            }
        }
        for (r in recorders) r.cut()

        return CaseResult(
            case,
            envs.indices.map { i ->
                EnvResult(
                    envIndex = i,
                    games = games[i].mapIndexed { g, r -> "${case.name}-e$i-g$g.pkr" to r },
                    decisions = decisions[i],
                    chainHex = EnvGolden.toHex(chains[i]),
                )
            },
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 파일
    // ─────────────────────────────────────────────────────────────────────────

    const val CHAINS_FILE = "chains.json"

    /**
     * `chains.json`. JSON 라이브러리 없이 손으로 쓴다 — env 는 의존성을 늘리지 않는다 (NFR-2).
     * 검증은 파싱이 아니라 **다시 쓴 텍스트와의 바이트 비교**다.
     */
    fun render(results: List<CaseResult>): String = buildString {
        appendLine("{")
        appendLine("  \"spec\": \"결정 관측 · h0 = 0x00*32 · h_n = SHA256(h_{n-1} || slot(u8) || obs_n(f32 LE)) · 외부 슬롯 왼쪽→오른쪽 · terminal 관측 제외\",")
        appendLine("  \"cases\": [")
        results.forEachIndexed { ci, r ->
            val c = r.case.config
            append("    {\"name\":\"${r.case.name}\",\"frames\":${r.case.frames},")
            append("\"layoutHash\":\"${c.layoutHash}\",\"dim\":${c.obsDim},")
            append("\"obs\":{\"includeLanding\":${c.obs.includeExpectedLanding},\"includeSideFlag\":${c.obs.includeSideFlag},")
            append("\"mirror\":${c.mirrorObservations},\"winningScore\":${c.winningScore}},")
            appendLine("\"envs\":[")
            r.envs.forEachIndexed { ei, e ->
                val files = e.games.joinToString(",") { "\"${it.first}\"" }
                append("      {\"envIndex\":${e.envIndex},\"games\":[$files],\"decisions\":${e.decisions},\"chain\":\"${e.chainHex}\"}")
                appendLine(if (ei < r.envs.size - 1) "," else "")
            }
            append("    ]}")
            appendLine(if (ci < results.size - 1) "," else "")
        }
        appendLine("  ]")
        appendLine("}")
    }

    const val CONSTANTS_FILE = "constants.json"

    /** `deriveSeed` 표의 축. 음수 base · 경계값 · 큰 env · k = 0 을 반드시 포함한다 (JS 의 imul · `>>>` 확인용). */
    private val SEED_BASES = intArrayOf(0, 1, -1, 7, 123456789, -987654321, Int.MAX_VALUE, Int.MIN_VALUE)
    private val SEED_ENVS = intArrayOf(0, 1, 2, 15, 255, 1_000_000)
    private val SEED_RALLIES = intArrayOf(0, 1, 2, 59, 100_000)

    /**
     * JS 가 Kotlin 코드를 읽지 않고 대조할 값들 — 레이아웃 해시 네 조합과 `deriveSeed` 표.
     * 골든 체인과 같은 디렉터리에 두고 같은 규칙(바이트 비교)으로 지킨다.
     */
    fun renderConstants(): String = buildString {
        appendLine("{")
        appendLine("  \"layouts\": [")
        val combos = listOf(true to false, false to false, true to true, false to true)
        combos.forEachIndexed { i, (landing, side) ->
            val opts = ObsSpec.Options(includeExpectedLanding = landing, includeSideFlag = side)
            append("    {\"includeLanding\":$landing,\"includeSideFlag\":$side,\"dim\":${ObsSpec.dim(opts)},\"layoutHash\":\"${ObsSpec.layoutHash(opts)}\"}")
            appendLine(if (i < combos.size - 1) "," else "")
        }
        appendLine("  ],")
        appendLine("  \"deriveSeed\": [")
        val rows = buildList {
            for (b in SEED_BASES) for (e in SEED_ENVS) for (k in SEED_RALLIES) add("[$b,$e,$k,${PikaEnv.deriveSeed(b, e, k)}]")
        }
        rows.chunked(5).forEachIndexed { i, chunk ->
            append("    ").append(chunk.joinToString(","))
            appendLine(if (i < (rows.size + 4) / 5 - 1) "," else "")
        }
        appendLine("  ]")
        appendLine("}")
    }

    fun computeAll(onProgress: (String) -> Unit = {}): List<CaseResult> = CASES.map {
        onProgress("${it.name} (환경 ${it.numEnvs}, ${it.frames}스텝)")
        run(it)
    }

    fun write(onProgress: (String) -> Unit = {}): List<CaseResult> {
        val results = computeAll(onProgress)
        val dir = EnvPaths.goldenObsDir
        Files.createDirectories(dir)
        // 케이스 구성이 바뀌어 사라진 리플레이가 남지 않게 지우고 다시 쓴다.
        Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".pkr") }.forEach(Files::delete) }
        for (r in results) for (e in r.envs) for ((name, replay) in e.games) {
            Files.write(dir.resolve(name), ReplayCodec.encode(replay))
        }
        dir.resolve(CHAINS_FILE).toFile().writeText(render(results))
        dir.resolve(CONSTANTS_FILE).toFile().writeText(renderConstants())
        return results
    }
}

/** `./gradlew :engine-kotlin:env:writeObsGolden` 의 진입점. */
object ObsGoldenMain {
    @JvmStatic
    fun main(args: Array<String>) {
        println("결정 관측 골든 재생성: ${EnvPaths.goldenObsDir}")
        val results = ObsGolden.write { println("  $it") }
        val files = results.sumOf { r -> r.envs.sumOf { it.games.size } }
        println("케이스 ${results.size}개 · 리플레이 ${files}개를 썼습니다.")
        println("⚠️ 무엇을 왜 바꿨는지 커밋 메시지에 적으세요.")
    }
}
