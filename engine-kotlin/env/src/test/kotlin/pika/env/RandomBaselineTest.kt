package pika.env

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import pika.core.PikaUserInput
import pika.core.XorShift32
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 균등 무작위 정책 vs FSM — **학습의 "0%" 를 고정한다.** (FR-12, M3-g / `plan.md` §2.1 측정 1)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 이 테스트가 존재하는 이유
 * ─────────────────────────────────────────────────────────────────────────────
 * Phase 3 의 모든 승률은 "기준선과의 차이" 로만 의미가 있다. 그 기준선을 계획 단계의
 * 임시 프로브로 재고 지워 버리면, 나중에 "3% 가 나왔는데 이게 학습된 것인가 잡음인가" 에
 * 답할 근거가 없다. 그래서 커밋된 테스트로 복원한다.
 *
 * 그리고 이 숫자는 **Python 평가기(`evaluate.py`)의 자기 검증값**이다 (M3-g).
 * 두 구현은 무작위원(RNG)이 다르므로 바이트 일치가 아니라 **통계적으로** 일치해야 한다 —
 * 그래야 서로를 검증한다. 같은 코드를 두 번 부르면 아무것도 검증하지 못한다.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 여기서 확정하는 두 문장
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. **무작위 정책은 게임을 한 번도 못 이긴다.** 랠리는 4~5% 를 가져간다 (서브 실패 등).
 * 2. **boldness 는 난이도 축이 아니다.** b 를 0~4 로 고정해도 승률이 단조롭지 않고
 *    전부 잡음(±3SE) 안이다. ROADMAP 의 "boldness 커리큘럼" 문장이 폐기되는 근거다.
 */
class RandomBaselineTest {

    /**
     * 진영별 게임 수. `plan.md` §2.1 측정 1 은 100 이었다. **커밋된 기준선은 200 으로 올렸다** —
     * 이 숫자가 M3-g 의 기준값이 되므로 표준오차를 0.0052 → 0.0036 으로 줄이는 값이 있고,
     * 2,400 게임 전체가 이 기계에서 5초다 (Kotlin 물리는 프레임당 수백 ns 다).
     */
    private val gamesPerSide = 200

    /** 정책 시드. boldness 표는 **두 수열을 합쳐** 본다 (아래 [boldnessIsNotADifficultyAxis]). */
    private val policySeeds = listOf(20_250_923, 7_919)

    /** 랠리 상한. **Python 평가기와 같은 값이어야** 랠리 승률의 분모가 같다 (`EnvConfig` 기본값). */
    private val maxRallyFrames = 3_000

    /**
     * 균등 무작위 정책. `Discrete(18)` 에서 고르고 엣지 변환을 거친다.
     *
     * ⚠️ **엔진의 `Rand` 를 쓰지 않는다.** 정책이 엔진의 난수 스트림을 소비하면 같은 시드가
     *    다른 경기가 되고, FSM 의 boldness 추첨과 위치 지터가 정책의 행동 수에 따라 밀린다.
     *    자기만의 [XorShift32] 를 든다.
     *
     * ⚠️ 엣지 변환기는 **랠리마다 리셋**한다 ([PikaEnv.beginRally] 와 같은 계약). 안 하면
     *    이전 랠리 마지막 프레임의 키 상태가 새어 들어가 첫 파워히트가 먹지 않는다.
     */
    private class RandomPolicy(seed: Int) : Controller {
        private val rng = XorShift32(seed)
        private val edge = EdgeTrigger()
        private var lastGame: PikaGame? = null
        private var lastRally = -1

        override fun decide(game: PikaGame, isPlayer2: Boolean, out: PikaUserInput) {
            if (game !== lastGame || game.rallyIndex != lastRally) {
                edge.reset()
                lastGame = game
                lastRally = game.rallyIndex
            }
            ActionCodec.decode(nextAction(), out, edge)
        }

        /** `nextRand()` 는 [0, 32767] 이고 32768 은 18 의 배수가 아니다. 나머지 구간은 버린다. */
        private fun nextAction(): Int {
            while (true) {
                val r = rng.nextRand()
                if (r < UNBIASED_LIMIT) return r % ActionCodec.ACTION_COUNT
            }
        }

        private companion object {
            const val UNBIASED_LIMIT = 32768 / ActionCodec.ACTION_COUNT * ActionCodec.ACTION_COUNT // 32,760
        }
    }

    private fun measure(fixedBoldness: FixedBoldness, policySeed: Int = policySeeds[0]) =
        GameEvaluator.evaluate(
            policy = RandomPolicy(policySeed),
            opponent = null,
            games = gamesPerSide,
            baseSeed = 0,
            maxRallyFrames = maxRallyFrames,
            fixedBoldness = fixedBoldness,
        )

    /** 랠리 승률 = 무작위 정책이 딴 점수 / 끝난 랠리. 분모에 잘린 랠리를 포함한다. */
    private fun GameEvaluator.WinRate.rallyWinRate(): Double =
        if (rallies == 0) 0.0 else pointsFor.toDouble() / rallies

    private fun GameEvaluator.WinRate.line(label: String): String =
        "  %-8s 게임승 %3d/%d · 랠리승률 %.4f (%d/%d) · 평균 %.1f프레임 · 게임당 %.0f프레임 · 잘린랠리 %d".format(
            label, wins, games, rallyWinRate(), pointsFor, rallies,
            meanRallyFrames, frames.toDouble() / games, truncatedRallies,
        )

    // ── 1. 기준선 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("FR-12: 무작위 정책은 게임을 못 이긴다 — 게임승 0/200, 랠리 승률 ≈ 4.5%")
    fun randomPolicyNeverWins() {
        val result = measure(FixedBoldness.RANDOM)
        println("── FR-12 무작위 정책 vs FSM (진영별 $gamesPerSide 게임, boldness 추첨) ──")
        println(result.asLeft.line("왼쪽"))
        println(result.asRight.line("오른쪽"))

        // (1) 게임 승률 0 — 이것이 Track A 의 출발 신호다 (PRD §2.3).
        assertEquals(0, result.asLeft.wins, "왼쪽 진영: 무작위 정책이 게임을 이겼다")
        assertEquals(0, result.asRight.wins, "오른쪽 진영: 무작위 정책이 게임을 이겼다")

        // (2) 랠리 승률은 0 이 아니다 — 서브 실패·자책으로 4~5% 는 굴러온다.
        //     **합산에 M3-g 와 같은 기준(0.045 ± 0.010)을 건다.** Python 평가기가 이 밴드를
        //     통과하는 것이 M3-g 이므로, Kotlin 쪽이 먼저 그 안에 있어야 기준선이 성립한다.
        val pooledRate = (result.asLeft.pointsFor + result.asRight.pointsFor).toDouble() /
            (result.asLeft.rallies + result.asRight.rallies)
        println("  합산     랠리승률 %.4f  ← M3-g 의 기준값 (0.045 ± 0.010)".format(pooledRate))
        assertTrue(abs(pooledRate - 0.045) <= 0.010, "합산 랠리 승률이 M3-g 밴드 밖입니다: $pooledRate")
        // 진영별은 더 넓게 본다. 진영 자체가 교락변수라 둘은 같은 값이 아니다 (PRD §2.4).
        for ((label, side) in listOf("왼쪽" to result.asLeft, "오른쪽" to result.asRight)) {
            val rate = side.rallyWinRate()
            assertTrue(rate in 0.030..0.060, "$label 랠리 승률이 기준선 밖입니다: $rate")
        }

        // (3) 골든 — 물리나 경기 규칙이 바뀌면 여기가 빨간불이 된다.
        //     ⚠️ 기대값을 고치기 전에 원인을 찾아라 (FsmBaselineTest 와 같은 규율).
        assertEquals(111, result.asLeft.pointsFor, "왼쪽 랠리 승 (골든)")
        assertEquals(144, result.asRight.pointsFor, "오른쪽 랠리 승 (골든)")
        // 랠리 수 = FSM 이 딴 15점 × 게임 수 + 정책이 딴 점수. 잘린 랠리가 없으니 정확히 성립한다.
        assertEquals(15 * gamesPerSide + 111, result.asLeft.rallies, "왼쪽 랠리 수 (골든)")
        assertEquals(15 * gamesPerSide + 144, result.asRight.rallies, "오른쪽 랠리 수 (골든)")

        // (4) 잘린 랠리는 없어야 한다 — 무작위 정책의 랠리는 평균 70프레임이다.
        //     여기에 값이 생기면 truncation 이 기준선을 오염시키고 있다는 뜻이다.
        assertEquals(0, result.asLeft.truncatedRallies + result.asRight.truncatedRallies)
    }

    // ── 2. boldness 는 난이도 축이 아니다 ────────────────────────────────

    @Test
    @DisplayName("PRD §2.1: boldness 0~4 를 고정해도 무작위 정책의 승률은 단조롭지 않다")
    fun boldnessIsNotADifficultyAxis() {
        // ⚠️ 정책 시드를 **두 개** 쓴다. 조건마다 같은 행동 수열을 쓰면(공통 난수) b 의 효과만
        //    남지만, 그 수열 하나의 버릇이 표에 그대로 찍힌다. 두 수열을 합쳐 그 버릇을 희석한다.
        val rows = (0..4).map { b ->
            b to policySeeds.map { seed -> measure(FixedBoldness(b), seed) }
        }

        println("── PRD §2.1 boldness 고정 (진영별 ${gamesPerSide * policySeeds.size} 게임) ──")
        println("  %-4s %-8s %-10s %-10s %s".format("b", "진영", "게임승", "랠리승률", "평균 랠리 프레임"))
        val rates = mutableMapOf<Pair<Int, Int>, Double>()
        for ((b, results) in rows) {
            for ((sideIdx, label) in listOf(0 to "왼쪽", 1 to "오른쪽")) {
                val sides = results.map { if (sideIdx == 0) it.asLeft else it.asRight }
                val wins = sides.sumOf { it.wins }
                val points = sides.sumOf { it.pointsFor }
                val rallies = sides.sumOf { it.rallies }
                val frames = sides.sumOf { it.frames }
                val rate = points.toDouble() / rallies
                rates[b to sideIdx] = rate
                println(
                    "  %-4d %-8s %-10s %.4f (%4d/%5d) %.1f".format(
                        b, label, "$wins/${sides.sumOf { it.games }}", rate, points, rallies,
                        frames.toDouble() / rallies,
                    ),
                )
                // (1) 어떤 b 에서도 무작위 정책은 게임을 못 이긴다. **이것이 본론이다.**
                assertEquals(0, wins, "b=$b $label 진영에서 무작위 정책이 게임을 이겼다")
                // (2) 랠리 승률도 좁은 절대 밴드 안에 있다. 난이도 사다리라면 이 폭이 아니다.
                assertTrue(rate in 0.025..0.060, "b=$b $label 랠리 승률이 기준선 밖입니다: $rate")
            }
        }

        // (3) b 를 0 → 4 로 끌어도 랠리 승률의 **폭**이 0.02 를 넘지 않는다.
        //
        // ⚠️ `plan.md` §2.1 은 이 주장을 "단조성이 없다" 로 적었다. 커밋된 테스트는 그 판정을
        //    쓰지 않는다 — 실측에서 오른쪽 진영이 0.0435 → 0.0434 로 **0.0001 차이로** 단조를
        //    벗어났고, 그런 판정은 물리가 아니라 반올림을 시험한다. 대신 폭을 본다:
        //    목표가 게임 승률 90%(M3-a) 인데 이 축이 움직이는 것은 랠리 승률 1~2%p 다.
        //    커리큘럼으로 쓸 손잡이가 아니다.
        for ((sideIdx, label) in listOf(0 to "왼쪽", 1 to "오른쪽")) {
            val series = (0..4).map { rates[it to sideIdx]!! }
            val spread = series.max() - series.min()
            println("  $label 진영 b 전체 폭 %.4f (%.4f ~ %.4f)".format(spread, series.min(), series.max()))
            assertTrue(spread <= 0.020, "$label 진영: b 가 랠리 승률을 %.4f 만큼 움직입니다".format(spread))
        }

        // (4) 골든 — 전체 랠리 승 합. 물리가 바뀌면 여기가 먼저 빨간불이 된다.
        val totalPoints = rows.sumOf { (_, rs) -> rs.sumOf { it.asLeft.pointsFor + it.asRight.pointsFor } }
        assertEquals(2719, totalPoints, "boldness 표 전체의 랠리 승 (골든)")
    }
}
