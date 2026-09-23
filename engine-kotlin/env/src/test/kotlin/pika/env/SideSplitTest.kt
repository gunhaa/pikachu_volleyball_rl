package pika.env

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import pika.core.Rand

/**
 * 진영 분할과 boldness 고정. (tasks.md P2, FR-3, FR-13, plan.md §4)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 이 테스트가 지키는 두 문장
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. **`swappedEnvs = 0` 은 기존 동작과 바이트 단위로 같다.** 이것이 아니면 Phase 2 의
 *    골든·결정론(M2-d)이 전부 재작성 대상이 된다.
 * 2. **전량 스왑은 진영을 뒤집어 구성한 것과 바이트 단위로 같다.** 스왑이 "슬롯 구성을
 *    바꾸는 것" 이상의 일을 하지 않는다는 뜻이고, 그래야 앞뒤 절반을 같은 배치에 섞어도
 *    각 절반이 여전히 정직한 표본이다.
 */
class SideSplitTest {

    /** 관측·보상·플래그를 전부 이어붙인 바이트열. 두 구성이 같은지는 이것으로 묻는다. */
    private fun trace(
        config: EnvConfig,
        numEnvs: Int,
        frames: Int,
        swappedEnvs: Int = 0,
        actionSeed: Int = 1,
    ): ByteArray {
        val vec = VectorEnv(config, numEnvs, swappedEnvs)
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

    /** 환경 [index] 한 칸만 뽑아 이어붙인다. 부분 스왑을 검사하려면 칸 단위로 봐야 한다. */
    private fun traceOfEnv(
        config: EnvConfig,
        numEnvs: Int,
        frames: Int,
        index: Int,
        swappedEnvs: Int = 0,
    ): ByteArray {
        val vec = VectorEnv(config, numEnvs, swappedEnvs)
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
            for (k in 0 until vec.slotCount) out.write(vec.rewards[index * vec.slotCount + k].toRawBits())
            out.write(vec.terminated[index].toInt())
            out.write(vec.truncated[index].toInt())
            out.write(vec.scores[index * 2])
            out.write(vec.scores[index * 2 + 1])
        }
        return out.toByteArray()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 진영 분할
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("swappedEnvs = 0 이면 기존 동작과 바이트 단위로 같다")
    fun zeroSwapIsByteIdentical() {
        val config = EnvConfig(slots = Slots.EXTERNAL_VS_FSM, baseSeed = 3)
        val vec = VectorEnv(config, 4)
        assertEquals(0, vec.swappedEnvs, "기본값이 0 이 아니면 Phase 2 의 골든이 전부 움직인다")
        assertArrayEquals(trace(config, 4, 600), trace(config, 4, 600, swappedEnvs = 0))
        for (i in 0 until 4) assertFalse(vec.isSwapped(i))
    }

    @Test
    @DisplayName("전량 스왑은 진영을 뒤집어 구성한 것과 바이트 단위로 같다")
    fun fullSwapEqualsMirroredConfig() {
        val numEnvs = 4
        val left = EnvConfig(slots = Slots.EXTERNAL_VS_FSM, baseSeed = 5)
        val right = EnvConfig(slots = Slots.FSM_VS_EXTERNAL, baseSeed = 5)
        assertArrayEquals(
            trace(right, numEnvs, 600),
            trace(left, numEnvs, 600, swappedEnvs = numEnvs),
            "스왑이 슬롯 구성 이상의 일을 하고 있다",
        )
    }

    @Test
    @DisplayName("부분 스왑: 앞쪽은 원래 진영, 뒤쪽은 뒤집힌 진영과 칸별로 일치한다")
    fun partialSwapKeepsBothHalvesHonest() {
        val numEnvs = 4
        val swapped = 2
        val left = EnvConfig(slots = Slots.EXTERNAL_VS_FSM, baseSeed = 11)
        val right = EnvConfig(slots = Slots.FSM_VS_EXTERNAL, baseSeed = 11)

        for (i in 0 until numEnvs - swapped) {
            assertArrayEquals(
                traceOfEnv(left, numEnvs, 400, i),
                traceOfEnv(left, numEnvs, 400, i, swappedEnvs = swapped),
                "앞쪽 env $i 가 스왑의 영향을 받았다",
            )
        }
        for (i in numEnvs - swapped until numEnvs) {
            // ⚠️ 같은 index 로 비교한다. 시드가 envIndex 의 함수이므로 인덱스가 어긋나면
            //    "다른 경기" 를 비교하게 되고 테스트가 아무것도 말하지 않는다.
            assertArrayEquals(
                traceOfEnv(right, numEnvs, 400, i),
                traceOfEnv(left, numEnvs, 400, i, swappedEnvs = swapped),
                "뒤쪽 env $i 가 오른쪽 진영이 아니다",
            )
        }
    }

    @Test
    @DisplayName("뒤쪽 swappedEnvs 개의 슬롯 구성이 실제로 뒤집혀 있다")
    fun swappedEnvsCarryFlippedSlots() {
        val vec = VectorEnv(EnvConfig(slots = Slots.EXTERNAL_VS_FSM), numEnvs = 6, swappedEnvs = 2)
        for (i in 0 until 4) {
            assertFalse(vec.isSwapped(i))
            assertEquals(Slots.EXTERNAL_VS_FSM, vec.envAt(i).config.slots)
            assertFalse(vec.envAt(i).game.physics.player1.isComputer, "왼쪽이 외부 정책이어야 한다")
        }
        for (i in 4 until 6) {
            assertTrue(vec.isSwapped(i))
            assertEquals(Slots.FSM_VS_EXTERNAL, vec.envAt(i).config.slots)
            assertFalse(vec.envAt(i).game.physics.player2.isComputer, "오른쪽이 외부 정책이어야 한다")
        }
    }

    @Test
    @DisplayName("스왑은 슬롯 수와 관측 레이아웃을 보존한다 — packed 레이아웃의 전제다")
    fun swapPreservesSlotCountAndLayout() {
        for (slots in listOf(Slots.EXTERNAL_VS_FSM, Slots.FSM_VS_EXTERNAL, Slots.EXTERNAL_VS_EXTERNAL)) {
            val config = EnvConfig(slots = slots)
            val flipped = config.copy(slots = Slots(slots.p2, slots.p1))
            assertEquals(config.slotCount, flipped.slotCount)
            assertEquals(config.obsDim, flipped.obsDim)
            assertEquals(config.layoutHash, flipped.layoutHash, "관측 레이아웃은 슬롯 구성과 무관하다")
        }
        val vec = VectorEnv(EnvConfig(slots = Slots.EXTERNAL_VS_EXTERNAL), numEnvs = 4, swappedEnvs = 2)
        assertEquals(2, vec.slotCount)
        assertEquals(4 * 2 * vec.obsDim, vec.observations.size)
    }

    @Test
    @DisplayName("swappedEnvs 가 범위를 벗어나면 만들 때 거부한다")
    fun swappedEnvsIsRangeChecked() {
        val config = EnvConfig()
        assertThrows(IllegalArgumentException::class.java) { VectorEnv(config, 4, swappedEnvs = 5) }
        assertThrows(IllegalArgumentException::class.java) { VectorEnv(config, 4, swappedEnvs = -1) }
        // 경계는 허용된다 — 전량 스왑은 "오른쪽만 학습" 이라는 정당한 구성이다.
        VectorEnv(config, 4, swappedEnvs = 0)
        VectorEnv(config, 4, swappedEnvs = 4)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // boldness 고정 (FR-13 — 진단 축이다)
    // ─────────────────────────────────────────────────────────────────────────

    /** 0, 1, 2, ... 를 차례로 내는 RNG. 소비 **횟수**를 세려고 쓴다. */
    private class CountingRand : Rand {
        var calls = 0
            private set

        override fun next(): Int = calls++ % 32768
    }

    @Test
    @DisplayName("fixedBoldness 는 매 랠리 유지된다")
    fun fixedBoldnessSurvivesEveryRally() {
        for (b in 0..4) {
            val config = EnvConfig(slots = Slots.EXTERNAL_VS_FSM, baseSeed = 2, fixedBoldness = b)
            val vec = VectorEnv(config, 2)
            vec.reset()
            val actions = EnvGolden.ActionSequence(1, 2, vec.slotCount)
            var rallies = 0
            repeat(3000) {
                vec.step(actions.next())
                for (i in 0 until 2) {
                    if (vec.terminated[i].toInt() != 0 || vec.truncated[i].toInt() != 0) rallies++
                    assertEquals(
                        b, vec.envAt(i).game.physics.player2.computerBoldness,
                        "boldness 가 $b 에서 벗어났다 (env $i)",
                    )
                }
            }
            assertTrue(rallies > 5, "랠리 경계를 충분히 밟지 못했다 — 테스트가 아무것도 시험하지 못한다")
        }
    }

    @Test
    @DisplayName("boldness 를 고정해도 추첨은 그대로 소비한다 — 난수 스트림이 밀리지 않는다")
    fun pinningDoesNotSkipTheDraw() {
        fun consumed(fixed: Int): Int {
            val rand = CountingRand()
            // External 끼리라 FSM 이 RNG 를 소비하지 않는다. 소비자는 initializeForNewRound 뿐이다.
            val game = PikaGame(rand, Slots.EXTERNAL_VS_EXTERNAL, fixedBoldness = FixedBoldness(fixed))
            repeat(5) { game.startNextRally() }
            return rand.calls
        }
        assertEquals(consumed(-1), consumed(2), "고정이 추첨을 건너뛰면 이후 모든 랠리가 다른 경기가 된다")
    }

    @Test
    @DisplayName("fixedBoldness = -1 이 기본이고, 그때는 아무것도 바뀌지 않는다")
    fun defaultIsDrawnPerRally() {
        assertEquals(-1, EnvConfig().fixedBoldness)
        assertEquals(FixedBoldness.RANDOM, PikaGame(CountingRand(), Slots.EXTERNAL_VS_EXTERNAL).fixedBoldness)

        val base = EnvConfig(slots = Slots.EXTERNAL_VS_FSM, baseSeed = 4)
        assertArrayEquals(
            trace(base, 2, 500),
            trace(base.copy(fixedBoldness = -1), 2, 500),
            "-1 은 '건드리지 않는다' 여야 한다",
        )
        // 반대로 고정하면 FSM 의 행동이 실제로 달라진다. 배선이 죽어 있지 않다는 증거다.
        assertNotEquals(
            trace(base, 2, 500).toList(),
            trace(base.copy(fixedBoldness = 4), 2, 500).toList(),
            "boldness 를 고정했는데 아무 변화가 없다 — 배선이 끊겼다",
        )
    }

    @Test
    @DisplayName("범위를 벗어난 fixedBoldness 는 거부한다")
    fun fixedBoldnessIsRangeChecked() {
        assertThrows(IllegalArgumentException::class.java) { EnvConfig(fixedBoldness = 5) }
        assertThrows(IllegalArgumentException::class.java) { EnvConfig(fixedBoldness = -2) }
        assertThrows(IllegalArgumentException::class.java) {
            PikaGame(CountingRand(), Slots.EXTERNAL_VS_EXTERNAL, fixedBoldness = FixedBoldness(7))
        }
    }
}
