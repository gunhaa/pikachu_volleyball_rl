package pika.analysis

import pika.conformance.RepoPaths
import pika.core.PikaUserInput
import pika.core.XorShift32
import pika.env.ActionCodec
import pika.env.Controller
import pika.env.EnvConfig
import pika.env.FixedBoldness
import pika.env.GameEvaluator
import pika.env.PikaEnv
import pika.env.PikaGame
import pika.env.RewardTerms
import pika.env.Slots
import pika.env.replay.Replay
import pika.env.replay.ReplayCodec
import pika.env.replay.ReplayRecorder
import pika.env.replay.SeedMode
import java.nio.file.Files
import java.nio.file.Path

/**
 * 골든 리플레이 세트 — JS 러너가 Kotlin 과 같은 경기를 계산하는지 보는 기준. (M4-b, plan.md §7.3)
 *
 * **결정론이다.** 같은 코드로 다시 만들면 바이트까지 같다 (`GoldenReplayTest` 가 확인한다).
 * 골든이 깨졌을 때 다시 뜨는 것으로 해결하지 않는다 — 먼저 무엇이 갈라졌는지 찾는다 (PRD §4).
 *
 * 게임 수는 적게, 프레임은 충분히 (합계 ≥ 100,000). 각 케이스는 규칙층의 서로 다른 갈래를 탄다.
 */
object GoldenReplays {

    class Case(val name: String, val purpose: String, val make: () -> Replay)

    val dir: Path get() = RepoPaths.root.resolve("engine-kotlin/env/golden/replay")

    val cases: List<Case> = listOf(
        Case("fsm-game-s0-l", "FSM vs FSM, GAME 규약, 시드 0, 서브 왼쪽 (M2-e 경로)") { evaluator(0, Slots.FSM_VS_FSM) },
        Case("fsm-game-s0-r", "FSM vs FSM, GAME 규약, 시드 0, 서브 오른쪽") { evaluator(0, Slots.FSM_VS_FSM, firstServeIsPlayer2 = true) },
        Case("fsm-game-s1-l", "FSM vs FSM, GAME 규약, 시드 1, 서브 왼쪽") { evaluator(1, Slots.FSM_VS_FSM) },
        Case("fsm-game-s1-r", "FSM vs FSM, GAME 규약, 시드 1, 서브 오른쪽") { evaluator(1, Slots.FSM_VS_FSM, firstServeIsPlayer2 = true) },
        Case("ext-fsm-rally", "무작위 External vs FSM, RALLY 규약 (Track A 평가 경로, 정책 왼쪽)") {
            env(EnvConfig(slots = Slots.EXTERNAL_VS_FSM, baseSeed = 42), envIndex = 5, actionSeed = 1)
        },
        Case("fsm-ext-rally", "FSM vs 무작위 External, RALLY 규약 (정책 오른쪽, 두 번째 게임 — 서브 오른쪽)") {
            env(EnvConfig(slots = Slots.FSM_VS_EXTERNAL, baseSeed = 42), envIndex = 60, actionSeed = 2, gameIndex = 1)
        },
        Case("ext-ext-rally", "무작위 External vs External, RALLY 규약 (FSM RNG 소비 없음)") {
            env(EnvConfig(slots = Slots.EXTERNAL_VS_EXTERNAL, baseSeed = 7), envIndex = 0, actionSeed = 3)
        },
        Case("trunc-150", "maxRallyFrames = 150 — FSM vs FSM 이라 truncation 이 다수, 짧은 랠리만 득점") {
            evaluator(9, Slots.FSM_VS_FSM, maxRallyFrames = 150, cap = 40_000)
        },
        // 무작위 정책의 랠리는 평균 80 프레임 안팎이라 150 으로는 거의 잘리지 않는다 — 60 으로 둔다.
        Case("ext-fsm-trunc-60", "maxRallyFrames = 60, RALLY 규약 — truncation 뒤의 재시드") {
            env(EnvConfig(slots = Slots.EXTERNAL_VS_FSM, baseSeed = 3, maxRallyFrames = 60), envIndex = 1, actionSeed = 4)
        },
        Case("boldness-0-4", "fixedBoldness = (0, 4) — 진영별 고정값") {
            evaluator(3, Slots.FSM_VS_FSM, fixedBoldness = FixedBoldness(0, 4))
        },
        Case("cap-800", "기록 상한 800 에서 잘린 미완 게임 (ended = false)") { evaluator(2, Slots.FSM_VS_FSM, cap = 800) },
        Case("powerhit", "powerHit 50% 무작위 External vs FSM, GAME 규약 — isPowerHit 경로") {
            evaluator(4, Slots.EXTERNAL_VS_FSM, p1 = RandomController(11, powerHitPercent = 50))
        },
    )

    /** 전부 만든다. 순서는 [cases] 순서. */
    fun generate(): List<Pair<Case, Replay>> = cases.map { it to it.make() }

    /** `dir` 에 `*.pkr` + `chains.json` 을 쓴다. */
    fun write(out: Path = dir): String {
        Files.createDirectories(out)
        val games = mutableListOf<Map<String, Any?>>()
        var totalFrames = 0L
        for ((case, replay) in generate()) {
            val file = "${case.name}.pkr"
            Files.write(out.resolve(file), ReplayCodec.encode(replay))
            val chain = ReplayChain.compute(replay)
            chain.play.orThrow()
            totalFrames += replay.frameCount
            games += linkedMapOf(
                "file" to file,
                "purpose" to case.purpose,
                "frames" to replay.frameCount,
                "rallies" to replay.rallyCount,
                "truncated" to replay.rallyOutcomes.count { it.toInt() == -1 },
                "score" to replay.finalScore.toList(),
                "ended" to replay.ended,
                "final" to chain.finalHex,
                "checkpoints" to chain.checkpoints,
            )
        }
        val doc = linkedMapOf(
            "spec" to "state-spec base 44 · h0 = 0x00*32 · h_n = SHA256(h_{n-1} || state_n) · game.step 직후 · 랠리 리셋 전",
            "interval" to ReplayChain.INTERVAL,
            "totalFrames" to totalFrames,
            "games" to games,
        )
        // 한 게임 한 줄 — diff 가 게임 단위로 읽힌다.
        val text = buildString {
            append("{\n")
            for ((k, v) in doc) if (k != "games") append("  ${Json.write(k)}: ${Json.write(v)},\n")
            append("  \"games\": [\n")
            games.forEachIndexed { i, g -> append("    ${Json.write(g)}${if (i < games.size - 1) "," else ""}\n") }
            append("  ]\n}\n")
        }
        Files.writeString(out.resolve("chains.json"), text)
        return "골든 리플레이 ${games.size} 개, 합계 $totalFrames 프레임 → $out"
    }

    // ── 경기 생성기 ────────────────────────────────────────────────────────

    private fun evaluator(
        seed: Int,
        slots: Slots,
        firstServeIsPlayer2: Boolean = false,
        maxRallyFrames: Int = 0,
        fixedBoldness: FixedBoldness = FixedBoldness.RANDOM,
        cap: Int = ReplayRecorder.DEFAULT_CAP,
        p1: Controller? = null,
    ): Replay {
        var out: Replay? = null
        val recorder = ReplayRecorder(SeedMode.GAME, cap) { if (out == null) out = it }
        try {
            GameEvaluator.playGame(
                seed, slots, p1 = p1, firstServeIsPlayer2 = firstServeIsPlayer2, maxRallyFrames = maxRallyFrames,
                fixedBoldness = fixedBoldness, recorder = recorder, maxGameFrames = cap.toLong() + 1,
            )
        } catch (e: IllegalStateException) {
            // maxGameFrames 를 넘겼다 — 기록기는 이미 잘린 리플레이를 내보냈다.
            if (out == null) throw e
        }
        return out!!
    }

    private fun env(config: EnvConfig, envIndex: Int, actionSeed: Int, gameIndex: Int = 0): Replay {
        val out = mutableListOf<Replay>()
        val env = PikaEnv(config, envIndex, ReplayRecorder(SeedMode.RALLY) { out += it })
        val obs = FloatArray(env.slotCount * env.obsDim)
        val rewards = FloatArray(env.slotCount)
        val terms = FloatArray(env.slotCount * RewardTerms.COUNT)
        val actions = ByteArray(env.slotCount)
        val rng = XorShift32(actionSeed)
        env.reset(obs, 0)
        while (out.size <= gameIndex) {
            for (k in actions.indices) actions[k] = (rng.nextRand() % ActionCodec.ACTION_COUNT).toByte()
            env.step(actions, 0, obs, 0, rewards, 0, terms, 0)
        }
        return out[gameIndex]
    }

    /** 결정론적 무작위 Controller. */
    class RandomController(seed: Int, private val powerHitPercent: Int) : Controller {
        private val rng = XorShift32(seed)
        override fun decide(game: PikaGame, isPlayer2: Boolean, out: PikaUserInput) {
            out.xDirection = rng.nextRand() % 3 - 1
            out.yDirection = rng.nextRand() % 3 - 1
            out.powerHit = if (rng.nextRand() % 100 < powerHitPercent) 1 else 0
        }
    }
}
