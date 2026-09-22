package pika.env

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import pika.core.XorShift32

/** 에피소드 의미론. (tasks.md P3, FR-7, FR-9 / M2-c 의 Kotlin 쪽) */
class PikaEnvTest {

    /** 한 환경의 버퍼 묶음. 테스트가 매번 offset 을 계산하지 않게 한다. */
    private class Buf(env: PikaEnv) {
        val obs = FloatArray(env.slotCount * env.obsDim)
        val rewards = FloatArray(env.slotCount)
        val terms = FloatArray(env.slotCount * RewardTerms.COUNT)
        val actions = ByteArray(env.slotCount)
    }

    private fun PikaEnv.stepRandom(b: Buf, rng: XorShift32): Int {
        for (k in b.actions.indices) b.actions[k] = (rng.nextRand() % ActionCodec.ACTION_COUNT).toByte()
        return step(b.actions, 0, b.obs, 0, b.rewards, 0, b.terms, 0)
    }

    private fun PikaEnv.stepWith(b: Buf, action: Int = 0): Int {
        for (k in b.actions.indices) b.actions[k] = action.toByte()
        return step(b.actions, 0, b.obs, 0, b.rewards, 0, b.terms, 0)
    }

    @Test
    @DisplayName("next-step autoreset: 종료 스텝의 obs ≠ 리셋 직후 obs, 그 다음 스텝의 obs = 리셋 직후 obs")
    fun nextStepAutoreset() {
        val config = EnvConfig(slots = Slots.EXTERNAL_VS_FSM, baseSeed = 1, maxRallyFrames = 0)
        val env = PikaEnv(config)
        val b = Buf(env)
        env.reset(b.obs, 0)

        // 랠리가 끝날 때까지.
        var flags = 0
        var frames = 0
        while (flags == 0) {
            flags = env.stepWith(b)
            check(++frames < 100_000) { "랠리가 끝나지 않았습니다" }
        }
        assertEquals(1, flags, "랠리 종료는 terminated 다")
        assertTrue(env.pendingReset, "종료 스텝에서는 아직 리셋하지 않는다")
        val terminalObs = b.obs.copyOf()
        val rallyAtTerminal = env.rallyCounter

        // 같은 시드로 새 환경을 만들어 "리셋 직후" 관측을 따로 구한다.
        val fresh = PikaEnv(config)
        val freshBuf = Buf(fresh)
        fresh.reset(freshBuf.obs, 0)
        assertFalse(
            terminalObs.contentEquals(freshBuf.obs),
            "⚠️ 종료 스텝이 이미 리셋된 관측을 돌려줬습니다 — same-step autoreset 입니다",
        )

        // 다음 스텝: 리셋이 일어나고, 행동은 버려진다.
        val afterFlags = env.stepWith(b, action = 17)
        assertEquals(0, afterFlags)
        assertFalse(env.pendingReset)
        assertEquals(rallyAtTerminal + 1, env.rallyCounter)
        assertEquals(0f, b.rewards[0], "리셋 스텝의 보상은 0 이다")
        assertEquals(0f, b.terms[RewardTerms.TIME_PENALTY], "리셋 스텝은 항도 전부 0 이다")
    }

    @Test
    @DisplayName("리셋 스텝의 행동은 버려진다 — 다른 행동을 줘도 같은 관측이 나온다")
    fun actionAtResetStepIsIgnored() {
        val config = EnvConfig(slots = Slots.EXTERNAL_VS_FSM, baseSeed = 2)

        fun runToResetStep(actionAtReset: Int): FloatArray {
            val env = PikaEnv(config)
            val b = Buf(env)
            env.reset(b.obs, 0)
            while (env.stepWith(b, action = 3) == 0) { /* 랠리 종료까지 같은 행동 */ }
            env.stepWith(b, action = actionAtReset)
            return b.obs.copyOf()
        }

        assertArrayEquals(runToResetStep(0), runToResetStep(17), "리셋 스텝에서 행동이 관측에 영향을 줬습니다")
    }

    @Test
    @DisplayName("엣지 트리거는 랠리 경계에서 초기화된다 — 이전 랠리의 키 상태가 새지 않는다")
    fun edgeTriggerResetsAtRallyBoundary() {
        val env = PikaEnv(EnvConfig(slots = Slots.EXTERNAL_VS_FSM, baseSeed = 3))
        val b = Buf(env)
        env.reset(b.obs, 0)

        val holdPowerHit = ActionCodec.encode(1, 0, powerHitHeld = true)
        while (env.stepWith(b, holdPowerHit) == 0) { /* 계속 누른 채로 랠리 종료까지 */ }
        assertTrue(env.edges[0].wasHeld, "종료 시점에는 여전히 누르고 있는 상태다")

        env.stepWith(b, holdPowerHit) // 리셋 스텝
        assertFalse(env.edges[0].wasHeld, "⚠️ 새 랠리로 키 상태가 새어 들어갔습니다")
    }

    @Test
    @DisplayName("maxRallyFrames 를 넘기면 truncated 다 (terminated 가 아니다)")
    fun truncationIsNotTermination() {
        val env = PikaEnv(EnvConfig(slots = Slots.EXTERNAL_VS_FSM, baseSeed = 4, maxRallyFrames = 5))
        val b = Buf(env)
        env.reset(b.obs, 0)

        var flags = 0
        var frames = 0
        while (flags == 0) {
            flags = env.stepWith(b)
            frames++
        }
        assertEquals(2, flags, "5프레임 랠리는 끝날 수 없다 — truncated 여야 한다")
        assertEquals(5, frames)
        assertEquals(0f, b.terms[RewardTerms.RALLY_WIN], "truncation 에는 승자가 없다")
        assertTrue(env.pendingReset)
    }

    @Test
    @DisplayName("보상 기본값은 rallyWin 뿐이다 — 셰이핑 가중치는 0 이다")
    fun defaultWeightsAreTerminalOnly() {
        // ⚠️ 상수 행동으로는 공을 거의 못 친다. 랜덤 정책끼리의 랠리가 평균 45프레임이라는
        //    측정(plan.md §2.3)이 바로 그 뜻이다. 그래서 넉넉한 프레임을 확률 행동으로 돈다.
        val env = PikaEnv(EnvConfig(slots = Slots.EXTERNAL_VS_EXTERNAL, baseSeed = 5))
        val b = Buf(env)
        env.reset(b.obs, 0)
        val rng = XorShift32(123)

        var touches = 0
        var terminals = 0
        // 오토리셋 스텝은 "프레임" 이 아니다 — 물리가 돌지 않으므로 항이 전부 0 이다.
        // 그 스텝을 세면 timePenalty 가 -1 이 아니라고 오해하게 된다.
        var isResetStep = false
        repeat(20_000) {
            val wasResetStep = isResetStep
            val flags = env.stepRandom(b, rng)
            isResetStep = flags != 0
            for (k in 0 until env.slotCount) {
                val base = k * RewardTerms.COUNT
                if (wasResetStep) {
                    assertEquals(0f, b.terms[base + RewardTerms.TIME_PENALTY], "리셋 스텝은 항이 전부 0 이다")
                    assertEquals(0f, b.rewards[k])
                } else if (flags == 0) {
                    assertEquals(-1f, b.terms[base + RewardTerms.TIME_PENALTY], "timePenalty 는 프레임마다 -1 이다")
                    assertEquals(0f, b.rewards[k], "종단 전에는 가중 보상이 0 이다 (셰이핑 가중치 0)")
                }
                if (b.terms[base + RewardTerms.BALL_TOUCH] > 0f) touches++
            }
            if (flags and 1 != 0) {
                terminals++
                // 종단: 한쪽 +1, 다른 쪽 -1 (영합)
                assertEquals(0f, b.rewards[0] + b.rewards[1], 1e-6f, "rallyWin 은 영합이다")
                assertEquals(1f, kotlin.math.abs(b.rewards[0]), 1e-6f)
                val winner = if (b.rewards[0] > 0) 0 else 1
                assertEquals(1f, b.terms[winner * RewardTerms.COUNT + RewardTerms.OPPONENT_MISS], "득점자에게 opponentMiss")
                assertEquals(0f, b.terms[(1 - winner) * RewardTerms.COUNT + RewardTerms.OPPONENT_MISS])
            }
        }
        assertTrue(touches > 0, "20,000 프레임 동안 공을 한 번도 안 쳤습니다 — ballTouch 항이 안 켜지고 있습니다")
        assertTrue(terminals > 100, "랠리가 너무 적습니다: $terminals")
    }

    @Test
    @DisplayName("crossedNet 은 공을 친 쪽에게, 공이 네트를 넘어간 프레임에만 준다")
    fun crossedNetGoesToTheHitter() {
        val env = PikaEnv(EnvConfig(slots = Slots.EXTERNAL_VS_EXTERNAL, baseSeed = 6))
        val b = Buf(env)
        env.reset(b.obs, 0)
        val rng = XorShift32(321)

        var crossings = 0
        repeat(20_000) {
            env.stepRandom(b, rng)
            val c0 = b.terms[0 * RewardTerms.COUNT + RewardTerms.CROSSED_NET]
            val c1 = b.terms[1 * RewardTerms.COUNT + RewardTerms.CROSSED_NET]
            assertFalse(c0 > 0f && c1 > 0f, "한 프레임에 양쪽 모두 네트를 넘길 수는 없다")
            if (c0 > 0f || c1 > 0f) crossings++
        }
        assertTrue(crossings > 0, "20,000 프레임 동안 공이 네트를 한 번도 안 넘었습니다")
    }

    @Test
    @DisplayName("같은 구성·같은 행동이면 관측이 완전히 같다 (M2-d 의 바닥)")
    fun sameConfigSameObservations() {
        val config = EnvConfig(slots = Slots.EXTERNAL_VS_FSM, baseSeed = 99)
        fun run(): List<Float> {
            val env = PikaEnv(config)
            val b = Buf(env)
            env.reset(b.obs, 0)
            val out = mutableListOf<Float>()
            repeat(500) {
                env.stepWith(b, action = it % ActionCodec.ACTION_COUNT)
                out += b.obs.toList()
                out += b.rewards.toList()
            }
            return out
        }
        assertEquals(run(), run())
    }

    @Test
    @DisplayName("환경 인덱스가 다르면 수열이 갈라진다 — 시드 유도가 실제로 섞인다")
    fun envIndexDecorrelates() {
        val config = EnvConfig(slots = Slots.EXTERNAL_VS_FSM, baseSeed = 0)
        val a = PikaEnv(config, envIndex = 0)
        val c = PikaEnv(config, envIndex = 1)
        val ba = Buf(a)
        val bc = Buf(c)
        a.reset(ba.obs, 0)
        c.reset(bc.obs, 0)
        // ⚠️ 상수 행동이면 한참 동안 같은 수열을 돈다 — 시드는 FSM 의 판단에만 들어가고,
        //    FSM 의 판단이 갈라지려면 공이 움직여야 한다. 행동을 바꿔 가며 충분히 돌린다.
        repeat(500) {
            a.stepWith(ba, action = it % ActionCodec.ACTION_COUNT)
            c.stepWith(bc, action = it % ActionCodec.ACTION_COUNT)
        }
        assertFalse(ba.obs.contentEquals(bc.obs), "환경 0 과 1 이 같은 수열을 돌고 있습니다")

        // 시드 유도는 세 입력 중 하나만 바뀌어도 갈라져야 한다.
        assertNotEquals(PikaEnv.deriveSeed(0, 0, 0), PikaEnv.deriveSeed(0, 0, 1))
        assertNotEquals(PikaEnv.deriveSeed(0, 0, 0), PikaEnv.deriveSeed(0, 1, 0))
        assertNotEquals(PikaEnv.deriveSeed(0, 0, 0), PikaEnv.deriveSeed(1, 0, 0))
    }

    @Test
    @DisplayName("M2-g: External vs External 구성에서는 FSM 코드 경로가 존재하지 않는다")
    fun trackBNeverTouchesFsm() {
        val env = PikaEnv(EnvConfig(slots = Slots.EXTERNAL_VS_EXTERNAL, baseSeed = 8))
        val b = Buf(env)
        env.reset(b.obs, 0)
        repeat(200) { env.stepWith(b, action = it % ActionCodec.ACTION_COUNT) }

        assertFalse(env.game.slots.usesFsm)
        assertFalse(env.game.physics.player1.isComputer)
        assertFalse(env.game.physics.player2.isComputer)
        assertEquals(2, env.slotCount, "Track B 는 두 슬롯 모두 외부 정책이다")
    }

    @Test
    @DisplayName("게임이 끝나면 새 게임이 시작되고 점수가 0 으로 돌아간다")
    fun newGameStartsAfterWinningScore() {
        val env = PikaEnv(EnvConfig(slots = Slots.EXTERNAL_VS_FSM, baseSeed = 10, winningScore = 1))
        val b = Buf(env)
        env.reset(b.obs, 0)

        while (env.stepWith(b) == 0) { /* 첫 랠리 종료까지 */ }
        assertTrue(env.game.gameEnded, "승점 1 이므로 첫 랠리로 게임이 끝난다")
        assertEquals(1, env.gameCounter)

        env.stepWith(b) // 리셋 스텝 — 새 게임
        assertEquals(2, env.gameCounter)
        assertArrayEquals(intArrayOf(0, 0), env.scores)
        assertFalse(env.game.gameEnded)
    }
}
