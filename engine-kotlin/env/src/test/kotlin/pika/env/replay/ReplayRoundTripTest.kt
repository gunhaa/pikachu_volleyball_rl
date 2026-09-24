package pika.env.replay

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pika.core.XorShift32
import pika.env.ActionCodec
import pika.env.Controller
import pika.env.EnvConfig
import pika.env.FixedBoldness
import pika.env.GameEvaluator
import pika.env.PikaEnv
import pika.env.RewardTerms
import pika.env.Slots
import pika.env.VectorEnv

/**
 * 리플레이 형식 v1 과 Kotlin 왕복. (tasks.md P1, M4-a 의 단위판, NFR-1)
 *
 * "기록 → 재생" 이 **기록기가 본 그 경기**를 다시 만드는지를 랠리 단위로 대조한다.
 * 재생기는 기록된 결과와 스스로 대조하므로 (`ReplayPlayer`), 여기서는 `ok` 와 함께
 * 기록 쪽 진실(GameOutcome · 환경의 점수)과도 한 번 더 맞춘다.
 */
class ReplayRoundTripTest {

    // ── 도우미 ─────────────────────────────────────────────────────────────

    /** PikaEnv 하나를 무작위 행동으로 돌려 [games] 게임을 기록한다. */
    private fun recordEnv(config: EnvConfig, games: Int, actionSeed: Int = 7, cap: Int = ReplayRecorder.DEFAULT_CAP): List<Replay> {
        val out = mutableListOf<Replay>()
        val env = PikaEnv(config, envIndex = 3, recorder = ReplayRecorder(SeedMode.RALLY, cap) { out += it })
        val obs = FloatArray(env.slotCount * env.obsDim)
        val rewards = FloatArray(env.slotCount)
        val terms = FloatArray(env.slotCount * RewardTerms.COUNT)
        val actions = ByteArray(env.slotCount)
        val rng = XorShift32(actionSeed)
        env.reset(obs, 0)
        var steps = 0
        while (out.size < games) {
            for (k in actions.indices) actions[k] = (rng.nextRand() % ActionCodec.ACTION_COUNT).toByte()
            env.step(actions, 0, obs, 0, rewards, 0, terms, 0)
            check(++steps < 5_000_000) { "게임이 모이지 않습니다" }
        }
        return out
    }

    private fun assertReplays(replays: List<Replay>) {
        for ((i, r) in replays.withIndex()) {
            val result = ReplayPlayer(r).play()
            assertTrue(result.ok, "게임 $i ($r): ${result.mismatch}")
            assertArrayEquals(r.finalScore, result.scores)
            assertEquals(r.frameCount, result.framesPlayed)
            assertArrayEquals(ReplayCodec.encode(r), ReplayCodec.encode(ReplayCodec.decode(ReplayCodec.encode(r))))
        }
    }

    /** 결정론적 무작위 Controller — powerHit 비율을 높여 파워히트 경로를 많이 탄다. */
    private fun randomController(seed: Int) = object : Controller {
        val rng = XorShift32(seed)
        override fun decide(game: pika.env.PikaGame, isPlayer2: Boolean, out: pika.core.PikaUserInput) {
            out.xDirection = rng.nextRand() % 3 - 1
            out.yDirection = rng.nextRand() % 3 - 1
            out.powerHit = if (rng.nextRand() % 4 == 0) 1 else 0
        }
    }

    // ── 코덱 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("코덱 왕복: 바이트 → 객체 → 바이트가 같다 (두 시드 규약, External 0·1·2개)")
    fun codecRoundTrip() {
        val replays = recordEnv(EnvConfig(slots = Slots.EXTERNAL_VS_FSM), games = 2) +
            recordEnv(EnvConfig(slots = Slots.EXTERNAL_VS_EXTERNAL, maxRallyFrames = 400), games = 1)
        val game = mutableListOf<Replay>()
        GameEvaluator.playGame(1, Slots.FSM_VS_FSM, recorder = ReplayRecorder(SeedMode.GAME) { game += it })
        for (r in replays + game) {
            val bytes = ReplayCodec.encode(r)
            val back = ReplayCodec.decode(bytes)
            assertArrayEquals(bytes, ReplayCodec.encode(back))
            assertEquals(r, back)
        }
        // FSM vs FSM 은 입력이 0 바이트다 (M4-g ≤ 256 B).
        val fsm = ReplayCodec.encode(game.single())
        assertTrue(fsm.size <= 256, "FSM vs FSM 리플레이 ${fsm.size} B")
    }

    @Test
    @DisplayName("코덱: magic · 버전 · 길이가 틀리면 예외다")
    fun codecRejects() {
        val game = mutableListOf<Replay>()
        GameEvaluator.playGame(2, Slots.FSM_VS_FSM, recorder = ReplayRecorder(SeedMode.GAME) { game += it })
        val bytes = ReplayCodec.encode(game.single())

        assertThrows<IllegalArgumentException> { ReplayCodec.decode(bytes.copyOf().also { it[0] = 'X'.code.toByte() }) }
        val v2 = assertThrows<IllegalArgumentException> { ReplayCodec.decode(bytes.copyOf().also { it[4] = 2 }) }
        assertTrue(v2.message!!.contains("버전"), v2.message)
        assertThrows<IllegalArgumentException> { ReplayCodec.decode(bytes.copyOf(bytes.size - 1)) }
        assertThrows<IllegalArgumentException> { ReplayCodec.decode(bytes + byteArrayOf(0)) }
        assertThrows<IllegalArgumentException> { ReplayCodec.decode(ByteArray(3)) }
    }

    // ── PikaEnv (RALLY 규약) ───────────────────────────────────────────────

    @Test
    @DisplayName("PikaEnv 기록 → 재생 일치: 무작위 External, 진영 좌·우, External vs External")
    fun pikaEnvRoundTrip() {
        for (slots in listOf(Slots.EXTERNAL_VS_FSM, Slots.FSM_VS_EXTERNAL, Slots.EXTERNAL_VS_EXTERNAL)) {
            val replays = recordEnv(EnvConfig(slots = slots, baseSeed = 11), games = 4)
            assertReplays(replays)
            assertTrue(replays.all { it.ended && it.seedMode == SeedMode.RALLY && it.edgeTrigger })
            // 첫 서브는 게임마다 번갈아 간다 (PikaEnv.startGame).
            assertEquals(listOf(false, true, false, true), replays.map { it.firstServeIsPlayer2 }, "$slots")
        }
    }

    @Test
    @DisplayName("VectorEnv: 끝난 게임이 (envIndex, gameInEnv) 로 쌓이고 drain 이 큐를 비운다")
    fun vectorEnvQueue() {
        val v = VectorEnv(EnvConfig(slots = Slots.EXTERNAL_VS_FSM, baseSeed = 5), numEnvs = 4, swappedEnvs = 2, recordReplays = true)
        v.reset()
        val rng = XorShift32(3)
        val actions = ByteArray(v.numEnvs * v.slotCount)
        val got = mutableListOf<VectorEnv.RecordedGame>()
        var steps = 0
        while (got.count { it.envIndex == 0 } < 2 || got.count { it.envIndex == 3 } < 2) {
            for (k in actions.indices) actions[k] = (rng.nextRand() % ActionCodec.ACTION_COUNT).toByte()
            v.step(actions)
            got += v.drainReplays()
            check(++steps < 2_000_000)
        }
        assertTrue(v.drainReplays().isEmpty(), "drain 뒤에는 비어 있어야 한다")
        for (i in 0 until v.numEnvs) {
            val mine = got.filter { it.envIndex == i }
            assertEquals(mine.indices.toList(), mine.map { it.gameInEnv }, "환경 $i 의 게임 번호")
            // 뒤쪽 절반은 진영이 뒤집혀 있다.
            val expected = if (v.isSwapped(i)) Slots.FSM_VS_EXTERNAL else Slots.EXTERNAL_VS_FSM
            assertTrue(mine.all { it.replay.slots == expected }, "환경 $i 의 슬롯")
        }
        assertReplays(got.map { it.replay })

        v.reset()
        assertTrue(v.drainReplays().isEmpty(), "reset 은 큐를 비운다")
        assertTrue(VectorEnv(EnvConfig(), 2).also { it.step(ByteArray(2)) }.drainReplays().isEmpty())
    }

    // ── GameEvaluator (GAME 규약) ──────────────────────────────────────────

    @Test
    @DisplayName("GameEvaluator FSM vs FSM 기록 → 재생 일치, GameOutcome 과도 같다")
    fun evaluatorRoundTrip() {
        val replays = mutableListOf<Replay>()
        val outcomes = mutableListOf<GameEvaluator.GameOutcome>()
        for (i in 0 until 6) {
            outcomes += GameEvaluator.playGame(
                seed = i / 2, slots = Slots.FSM_VS_FSM, firstServeIsPlayer2 = i % 2 == 1,
                recorder = ReplayRecorder(SeedMode.GAME) { replays += it },
            )
        }
        assertReplays(replays)
        for ((r, o) in replays.zip(outcomes)) {
            assertArrayEquals(o.scores, r.finalScore)
            assertEquals(o.winner, r.winner)
            assertEquals(o.rallies, r.rallyCount)
            assertEquals(o.frames, r.frameCount.toLong())
            assertEquals(0, r.inputs.size, "FSM 입력은 적지 않는다")
        }
    }

    @Test
    @DisplayName("GameEvaluator External vs FSM (무작위 Controller) 기록 → 재생 일치")
    fun evaluatorExternalRoundTrip() {
        val replays = mutableListOf<Replay>()
        for (seed in 0 until 3) {
            GameEvaluator.playGame(
                seed, Slots.EXTERNAL_VS_FSM, p1 = randomController(seed + 100),
                recorder = ReplayRecorder(SeedMode.GAME) { replays += it },
            )
            GameEvaluator.playGame(
                seed, Slots.EXTERNAL_VS_EXTERNAL, p1 = randomController(seed + 200), p2 = randomController(seed + 300),
                maxRallyFrames = 600, recorder = ReplayRecorder(SeedMode.GAME) { replays += it },
            )
        }
        assertReplays(replays)
    }

    // ── 경계 조건 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("truncation(maxRallyFrames = 200): 재생이 스스로 판정한 -1 이 기록과 같다")
    fun truncation() {
        val env = recordEnv(EnvConfig(slots = Slots.EXTERNAL_VS_FSM, maxRallyFrames = 200), games = 3)
        val game = mutableListOf<Replay>()
        GameEvaluator.playGame(4, Slots.FSM_VS_FSM, maxRallyFrames = 200, recorder = ReplayRecorder(SeedMode.GAME) { game += it })
        val all = env + game
        assertTrue(all.sumOf { r -> r.rallyOutcomes.count { it.toInt() == RallyOutcome.TRUNCATED } } > 0, "truncation 이 일어나야 한다")
        assertReplays(all)

        // 기록을 한 칸 바꾸면 재생이 알아챈다 — 재생기가 스스로 틀렸는지 아는가.
        val r = game.single()
        val k = r.rallyOutcomes.indexOfFirst { it.toInt() == RallyOutcome.TRUNCATED }
        val bytes = ReplayCodec.encode(r)
        val pos = 18 + 4 * r.seeds.size + 5 * k + 4
        bytes[pos] = 0
        val bad = ReplayCodec.decode(bytes)
        assertFalse(ReplayPlayer(bad).play().ok, "outcome 을 바꿨는데 검증을 통과했다")
    }

    @Test
    @DisplayName("boldness 고정: 진영별 값 (0, 4) 이 재생에서도 걸린다")
    fun fixedBoldness() {
        val replays = mutableListOf<Replay>()
        GameEvaluator.playGame(3, Slots.FSM_VS_FSM, fixedBoldness = FixedBoldness(0, 4), recorder = ReplayRecorder(SeedMode.GAME) { replays += it })
        replays += recordEnv(EnvConfig(slots = Slots.FSM_VS_EXTERNAL, fixedBoldness = 2), games = 2)
        assertEquals(FixedBoldness(0, 4), replays[0].fixedBoldness)
        assertEquals(FixedBoldness(2, 2), replays[1].fixedBoldness)
        assertReplays(replays)

        // 고정값을 지우면 다른 경기가 된다 — 재생이 boldness 를 실제로 쓴다는 증거.
        val r = replays[0]
        val stripped = Replay(
            r.slots, r.firstServeIsPlayer2, r.ended, r.edgeTrigger, r.seedMode, r.winningScore,
            FixedBoldness.RANDOM, r.maxRallyFrames, r.seeds, r.rallyFrames, r.rallyOutcomes, r.finalScore,
            r.frameCount, r.inputs,
        )
        assertFalse(ReplayPlayer(stripped).play().ok)
    }

    @Test
    @DisplayName("미완 게임(cap = 500): ended = false, 마지막 랠리 -2, 재생은 frameCount 에서 멈춘다")
    fun unfinishedGame() {
        val game = mutableListOf<Replay>()
        GameEvaluator.playGame(0, Slots.FSM_VS_FSM, recorder = ReplayRecorder(SeedMode.GAME, cap = 500) { game += it })
        val env = recordEnv(EnvConfig(slots = Slots.EXTERNAL_VS_FSM, maxRallyFrames = 0), games = 1, cap = 500)
        for (r in game + env) {
            assertFalse(r.ended)
            assertEquals(500, r.frameCount)
            assertEquals(RallyOutcome.UNFINISHED, r.rallyOutcomes.last().toInt())
            assertEquals(null, r.winner)
        }
        assertReplays(game + env)
    }

    @Test
    @DisplayName("상한 경계: 정확히 cap 프레임에 끝난 게임은 ended = true, cap - 1 이면 잘린다 (evaluate.py 와 같은 정의)")
    fun capBoundary() {
        val frames = GameEvaluator.playGame(5, Slots.FSM_VS_FSM).frames.toInt()
        fun record(cap: Int): Replay {
            val out = mutableListOf<Replay>()
            GameEvaluator.playGame(5, Slots.FSM_VS_FSM, recorder = ReplayRecorder(SeedMode.GAME, cap) { out += it })
            return out.single()
        }
        val exact = record(frames)
        assertTrue(exact.ended)
        assertEquals(frames, exact.frameCount)
        val cut = record(frames - 1)
        assertFalse(cut.ended)
        assertEquals(frames - 1, cut.frameCount)
        assertReplays(listOf(exact, cut))
    }

    // ── NFR-1 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("기록을 켜도 관측 · 보상 · 플래그 · 점수가 바이트 단위로 같다")
    fun recordingDoesNotChangeEnv() {
        val config = EnvConfig(slots = Slots.EXTERNAL_VS_FSM, baseSeed = 9, maxRallyFrames = 500)
        val off = VectorEnv(config, numEnvs = 4, swappedEnvs = 2)
        val on = VectorEnv(config, numEnvs = 4, swappedEnvs = 2, recordReplays = true)
        off.reset()
        on.reset()
        val rng = XorShift32(1)
        val actions = ByteArray(4)
        var games = 0
        repeat(30_000) {
            for (k in actions.indices) actions[k] = (rng.nextRand() % ActionCodec.ACTION_COUNT).toByte()
            off.step(actions)
            on.step(actions)
            assertArrayEquals(off.observations, on.observations)
            assertArrayEquals(off.rewards, on.rewards)
            assertArrayEquals(off.rewardTerms, on.rewardTerms)
            assertArrayEquals(off.terminated, on.terminated)
            assertArrayEquals(off.truncated, on.truncated)
            assertArrayEquals(off.scores, on.scores)
            games += on.drainReplays().size
        }
        assertTrue(games > 0, "비교 구간에 끝난 게임이 있어야 기록 경로를 탄 것이다")
    }
}
