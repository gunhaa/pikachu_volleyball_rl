package pika.env

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * M2-d — 결정론. (tasks.md P4, FR-10)
 *
 * `(seed, 행동 시퀀스, 구성)` 이 같으면 관측·보상 바이트가 **완전히** 같아야 한다.
 * 이것이 깨지면 Phase 8 의 두 트랙 비교도, Phase 4 의 리플레이도 성립하지 않는다.
 * **타협 대상이 아니다** (PRD §4).
 */
class DeterminismTest {

    /** 한 케이스를 돌려 관측·보상·플래그를 전부 이어붙인 바이트열로 만든다. */
    private fun trace(config: EnvConfig, numEnvs: Int, frames: Int, actionSeed: Int = 1): ByteArray {
        val vec = VectorEnv(config, numEnvs)
        vec.reset()
        val actions = EnvGolden.ActionSequence(actionSeed, numEnvs, vec.slotCount)
        val obsBytes = ByteArray(vec.observations.size * 4)
        val rewardBytes = ByteArray(vec.rewards.size * 4)
        val out = java.io.ByteArrayOutputStream()
        repeat(frames) {
            vec.step(actions.next())
            EnvGolden.packFloats(vec.observations, obsBytes)
            EnvGolden.packFloats(vec.rewards, rewardBytes)
            out.write(obsBytes)
            out.write(rewardBytes)
            out.write(vec.terminated)
            out.write(vec.truncated)
        }
        return out.toByteArray()
    }

    /** 환경 [index] 의 관측만 뽑아 이어붙인다. 벡터 크기 불변성을 보려면 이것이 필요하다. */
    private fun traceOfEnv(config: EnvConfig, numEnvs: Int, frames: Int, index: Int): ByteArray {
        val vec = VectorEnv(config, numEnvs)
        vec.reset()
        val actions = EnvGolden.ActionSequence(1, numEnvs, vec.slotCount)
        val stride = vec.slotCount * vec.obsDim
        val slice = FloatArray(stride)
        val bytes = ByteArray(stride * 4)
        val out = java.io.ByteArrayOutputStream()
        repeat(frames) {
            vec.step(actions.next())
            vec.observations.copyInto(slice, 0, index * stride, (index + 1) * stride)
            EnvGolden.packFloats(slice, bytes)
            out.write(bytes)
            out.write(vec.rewards[index * vec.slotCount].toRawBits())
            out.write(vec.terminated[index].toInt())
            out.write(vec.truncated[index].toInt())
        }
        return out.toByteArray()
    }

    @Test
    @DisplayName("M2-d: 같은 (시드, 행동, 구성) 을 다시 돌리면 바이트가 완전히 같다")
    fun rerunIsByteIdentical() {
        for (slots in listOf(Slots.EXTERNAL_VS_FSM, Slots.EXTERNAL_VS_EXTERNAL)) {
            val config = EnvConfig(slots = slots, baseSeed = 13)
            assertArrayEquals(trace(config, 4, 800), trace(config, 4, 800), "$slots 에서 재실행이 갈라졌습니다")
        }
    }

    @Test
    @DisplayName("M2-d: 벡터 크기를 1 · 4 · 256 으로 바꿔도 환경 0 의 수열은 그대로다")
    fun vectorSizeDoesNotChangeEnvZero() {
        val config = EnvConfig(slots = Slots.EXTERNAL_VS_FSM, baseSeed = 5)
        val frames = 400
        val n1 = traceOfEnv(config, 1, frames, 0)
        val n4 = traceOfEnv(config, 4, frames, 0)
        val n256 = traceOfEnv(config, 256, frames, 0)

        assertArrayEquals(n1, n4, "N=1 과 N=4 에서 환경 0 이 갈라졌습니다")
        assertArrayEquals(n1, n256, "N=1 과 N=256 에서 환경 0 이 갈라졌습니다")
        assertTrue(n1.isNotEmpty())
    }

    @Test
    @DisplayName("환경 3 의 수열도 벡터 크기와 무관하다 — 0번만 맞는 우연이 아님을 본다")
    fun vectorSizeDoesNotChangeEnvThree() {
        val config = EnvConfig(slots = Slots.EXTERNAL_VS_EXTERNAL, baseSeed = 2)
        assertArrayEquals(traceOfEnv(config, 4, 300, 3), traceOfEnv(config, 64, 300, 3))
    }

    @Test
    @DisplayName("구성이 다르면 수열도 다르다 — 결정론이 '아무것도 안 변한다' 가 되지 않게 한다")
    fun differentConfigsDiffer() {
        val base = EnvConfig(slots = Slots.EXTERNAL_VS_FSM, baseSeed = 1)
        val reference = trace(base, 2, 300)
        val variants = mapOf(
            "baseSeed" to base.copy(baseSeed = 2),
            "landing" to base.copy(obs = ObsSpec.Options(includeExpectedLanding = false)),
            "sideFlag" to base.copy(obs = ObsSpec.Options(includeSideFlag = true)),
            "edgeTrigger" to base.copy(edgeTriggerPowerHit = false),
            "weights" to base.copy(rewardWeights = RewardWeights(ballTouch = 1f)),
            "slots" to base.copy(slots = Slots.FSM_VS_EXTERNAL),
        )
        for ((name, config) in variants) {
            assertFalse(reference.contentEquals(trace(config, 2, 300)), "$name 을 바꿨는데 수열이 같습니다")
        }

        // ⚠️ 미러링은 오른쪽 슬롯에만 걸린다. Track A 는 외부 슬롯이 왼쪽 하나뿐이라
        //    미러링을 꺼도 관측이 바뀌지 않는다 — 그것이 정상이다. 시험하려면 오른쪽이
        //    외부인 구성이어야 한다.
        val bothSides = base.copy(slots = Slots.EXTERNAL_VS_EXTERNAL)
        assertArrayEquals(
            trace(base, 2, 300),
            trace(base.copy(mirrorObservations = false), 2, 300),
            "Track A 에서는 미러링이 관측에 닿지 않는다 (외부 슬롯이 왼쪽뿐이다)",
        )
        assertFalse(
            trace(bothSides, 2, 300).contentEquals(trace(bothSides.copy(mirrorObservations = false), 2, 300)),
            "오른쪽 슬롯이 외부인데도 미러링이 관측을 안 바꿉니다",
        )
    }

    @Test
    @DisplayName("M2-g: Track B 구성에서는 FSM 코드 경로가 존재하지 않는다 (불변식)")
    fun trackBHasNoFsm() {
        val config = EnvConfig(slots = Slots.EXTERNAL_VS_EXTERNAL, baseSeed = 4)
        val vec = VectorEnv(config, 8)
        vec.reset()
        val actions = EnvGolden.ActionSequence(1, 8, vec.slotCount)
        repeat(500) { vec.step(actions.next()) }

        for (i in 0 until vec.numEnvs) {
            val game = vec.envAt(i).game
            assertFalse(game.slots.usesFsm, "환경 $i 의 슬롯 구성에 FSM 이 있습니다")
            assertFalse(game.physics.player1.isComputer, "환경 $i player1 이 컴퓨터입니다")
            assertFalse(game.physics.player2.isComputer, "환경 $i player2 가 컴퓨터입니다")
        }
        // isComputer 가 false 면 letComputerDecideUserInput 분기에 **들어갈 수 없다**
        // (PhysicsEngine.kt:129). 셈이 아니라 불변식이다 — 더 싸고 더 확실하다 (plan.md §9).
        assertEquals(2, vec.slotCount)
    }

    @Test
    @DisplayName("행동 시퀀스는 벡터 크기와 무관하다 — 시험 도구가 시험 대상을 오염시키지 않는다")
    fun actionSequenceIsPerEnv() {
        val a = EnvGolden.ActionSequence(1, numEnvs = 1, slotCount = 1)
        val b = EnvGolden.ActionSequence(1, numEnvs = 64, slotCount = 1)
        repeat(100) {
            val fromA = a.next()[0]
            val fromB = b.next()[0]
            assertEquals(fromA, fromB, "환경 0 의 행동이 벡터 크기에 따라 달라집니다")
        }
    }
}
