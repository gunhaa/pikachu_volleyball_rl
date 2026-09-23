package pika.env

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import pika.core.PikaUserInput
import pika.core.Rand
import pika.core.XorShift32

/**
 * FSM(b_left) × FSM(b_right) 행렬 — **boldness 가 실제로 무엇을 바꾸는가.** (`plan.md` §2.1 측정 2·3)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 이 표가 폐기하는 것과 확정하는 것
 * ─────────────────────────────────────────────────────────────────────────────
 * ROADMAP 은 "boldness 커리큘럼 0 → 4" 로 난이도를 올릴 계획이었다. 이 표가 그것을
 * 폐기한다. 대신 **랠리 길이의 손잡이**라는 사실을 확정한다 — b 가 커지면 착지점 추적
 * 데드존(`boldness + 8`)이 넓어지고 점프 임계(`ball.y < 10*b + 84`)가 올라가 랠리가 짧아진다.
 *
 * 그 두 문장이 Phase 3 의 설계에 직접 들어간다.
 *
 * 1. **난이도 축이 없다** → Track A 는 처음부터 완전한 FSM 을 상대한다 (커리큘럼 없음).
 * 2. **b 는 랠리 길이를 바꾼다** → `maxRallyFrames` 와 γ(`plan.md` §5)는 b 의 함수다.
 * 3. **b 조합에 따라 랠리가 끝나지 않을 수 있다** (측정 3) → `maxRallyFrames` 는 선택이 아니다.
 *
 * ⚠️ 이 테스트는 FSM vs FSM 이므로 [RandomBaselineTest] 와 다른 것을 잰다. 저쪽은 "정책의
 *    0%", 이쪽은 "상대의 성질" 이다. 둘을 한 파일에 두면 실패했을 때 무엇이 깨졌는지 모른다.
 */
class BoldnessProbeTest {

    /**
     * 조합당 게임 수. `plan.md` §2.1 은 50 이었다. **20 으로 줄였다** — 셀 하나가 300 랠리
     * 이상이라 랠리 길이(이 표의 본론)의 표준오차는 이미 충분하고, FSM vs FSM 게임은
     * 게임당 15,000 프레임이 넘어 50 이면 이 테스트 하나가 20초를 먹는다.
     */
    private val gamesPerCell = 20

    /** `EnvConfig` 기본값과 같다. 측정 3 때문에 **0 으로 둘 수 없다** (아래 참고). */
    private val maxRallyFrames = 3_000

    private data class Cell(
        val leftWins: Int,
        val rallies: Int,
        val frames: Long,
        val truncated: Int,
    ) {
        val meanRallyFrames: Double get() = frames.toDouble() / rallies
        val truncatedRatio: Double get() = truncated.toDouble() / rallies
    }

    private fun cell(bLeft: Int, bRight: Int): Cell {
        var leftWins = 0
        var rallies = 0
        var frames = 0L
        var truncated = 0
        for (i in 0 until gamesPerCell) {
            val outcome = GameEvaluator.playGame(
                seed = i / 2,
                slots = Slots.FSM_VS_FSM,
                firstServeIsPlayer2 = i % 2 == 1,
                maxRallyFrames = maxRallyFrames,
                fixedBoldness = FixedBoldness(bLeft, bRight),
            )
            if (outcome.winner == 0) leftWins++
            rallies += outcome.rallies
            frames += outcome.frames
            truncated += outcome.truncatedRallies
        }
        return Cell(leftWins, rallies, frames, truncated)
    }

    @Test
    @DisplayName("PRD §2.1: 진영 효과가 boldness 효과를 덮고, b 는 랠리 길이를 줄인다")
    fun boldnessMatrix() {
        val matrix = Array(5) { bl -> Array(5) { br -> cell(bl, br) } }

        println("── FSM(b_left) × FSM(b_right), 조합당 $gamesPerCell 게임 ──")
        println("   칸 = 왼쪽승/$gamesPerCell · 평균 랠리 프레임 · 잘린 랠리%")
        print("        ")
        for (br in 0..4) print("       b_r=%d".format(br))
        println()
        for (bl in 0..4) {
            print("  b_l=%d ".format(bl))
            for (br in 0..4) {
                val c = matrix[bl][br]
                print("  %2d·%4.0f·%3.1f%%".format(c.leftWins, c.meanRallyFrames, c.truncatedRatio * 100))
            }
            println()
        }

        // ── (1) 진영 효과가 boldness 효과를 완전히 덮는다 ──────────────────
        // 25칸 전부에서 왼쪽이 압도한다. 이것이 "한 진영에서만 재면 실력이 아니라 진영을
        // 재게 된다" 의 근거이고, M3-b(각 진영 단독 ≥ 80%)가 타협 불가인 이유다.
        val totalLeftWins = matrix.sumOf { row -> row.sumOf { it.leftWins } }
        val cells = 25 * gamesPerCell
        println("  왼쪽 총 승 %d/%d (%.3f)".format(totalLeftWins, cells, totalLeftWins.toDouble() / cells))
        assertTrue(
            totalLeftWins.toDouble() / cells >= 0.90,
            "왼쪽 승률이 $totalLeftWins/$cells 입니다 — 좌우 비대칭(PRD §2.4)이 사라졌다면 물리가 바뀐 것이다",
        )
        for (bl in 0..4) for (br in 0..4) {
            assertTrue(
                matrix[bl][br].leftWins >= gamesPerCell * 0.5,
                "b_l=$bl b_r=$br 칸에서 왼쪽이 ${matrix[bl][br].leftWins}/$gamesPerCell 만 이겼습니다 — " +
                    "boldness 가 진영 효과를 뒤집을 수 있다면 커리큘럼 논의가 다시 열린다",
            )
        }

        // ── (2) b 는 랠리 길이의 손잡이다 ─────────────────────────────────
        // 행·열의 주변 평균이 b 가 커질수록 줄어든다. 이 방향이 `maxRallyFrames` 와 γ 의 근거다.
        val rowMeans = (0..4).map { bl -> matrix[bl].sumOf { it.frames }.toDouble() / matrix[bl].sumOf { it.rallies } }
        val colMeans = (0..4).map { br -> (0..4).sumOf { matrix[it][br].frames }.toDouble() / (0..4).sumOf { matrix[it][br].rallies } }
        println("  행 평균(b_left)  " + rowMeans.joinToString(" ") { "%.0f".format(it) })
        println("  열 평균(b_right) " + colMeans.joinToString(" ") { "%.0f".format(it) })
        assertTrue(rowMeans[0] / rowMeans[4] >= 1.3, "b_left 0 → 4 의 랠리 길이 비가 %.2f 입니다".format(rowMeans[0] / rowMeans[4]))
        assertTrue(colMeans[0] / colMeans[4] >= 1.3, "b_right 0 → 4 의 랠리 길이 비가 %.2f 입니다".format(colMeans[0] / colMeans[4]))

        // ── (3) 골든 ──────────────────────────────────────────────────────
        val totalRallies = matrix.sumOf { row -> row.sumOf { it.rallies } }
        val totalFrames = matrix.sumOf { row -> row.sumOf { it.frames } }
        val totalTruncated = matrix.sumOf { row -> row.sumOf { it.truncated } }
        println("  합계 랠리 %d · 프레임 %d · 잘린 랠리 %d".format(totalRallies, totalFrames, totalTruncated))
        assertEquals(493, totalLeftWins, "왼쪽 총 승 (골든)")
        assertEquals(10_190, totalRallies, "총 랠리 (골든)")
        assertEquals(7_610_105L, totalFrames, "총 프레임 (골든)")
        assertEquals(84, totalTruncated, "잘린 랠리 (골든)")
    }

    // ── 측정 3: 끝나지 않는 랠리 ──────────────────────────────────────────

    @Test
    @DisplayName("plan.md §2.1 측정 3: 어떤 b 조합·시드는 랠리가 끝나지 않는다")
    fun someBoldnessPairsNeverEndARally() {
        // ⚠️ 이 케이스가 `maxRallyFrames` 를 **필수**로 만든다. 기본 구성(매 랠리 추첨)에서는
        //    관측되지 않으므로, 이 테스트가 없으면 "실전에서 안 나오니 상한은 사치" 라는
        //    판단으로 되돌아갈 수 있다. 무한 랠리는 평가를 영원히 멈추게 한다 (`plan.md` §9.4).
        //
        // ⚠️ `plan.md` 가 적어 둔 좌표(b=(0,4), seed=14)는 **여기서 재현되지 않는다.** 그 값은
        //    삭제된 임시 프로브의 시드 규약에서 나온 것이다. 시드 0..300 × 첫 서브 2가지를
        //    훑어 이 테스트의 규약에서 같은 현상을 내는 좌표를 다시 찾았다:
        //    b=(0,1) seed=45, b=(0,2) seed=48, b=(0,3) seed=44 — 셋 다 첫 서브가 오른쪽이고
        //    2,000,000 프레임까지 끝나지 않는다. 현상은 같고 좌표만 바뀐 것이다.
        val budget = 200_000
        val rng = XorShift32(45)
        val game = PikaGame(
            Rand { rng.nextRand() },
            Slots.FSM_VS_FSM,
            firstServeIsPlayer2 = true,
            fixedBoldness = FixedBoldness(p1 = 0, p2 = 1),
        )
        val inputs = arrayOf(PikaUserInput(), PikaUserInput())
        repeat(budget) { game.step(inputs) }

        println("── 측정 3: b=(0, 1) seed=45 — ${game.rallyFrames} 프레임 동안 첫 랠리가 끝나지 않았다")
        assertEquals(0, game.rallyIndex, "첫 랠리가 끝났습니다 — 측정 3 이 재현되지 않는다")
        assertEquals(budget, game.rallyFrames, "랠리 프레임이 예산과 다릅니다")
        assertFalse(game.gameEnded)
        assertEquals(listOf(0, 0), game.scores.toList(), "득점이 있었다면 랠리가 끝난 것이다")

        // 상한을 걸면 같은 조합이 정상적으로 끝난다. 이것이 `maxRallyFrames` 의 계약이다.
        val outcome = GameEvaluator.playGame(
            seed = 45,
            slots = Slots.FSM_VS_FSM,
            firstServeIsPlayer2 = true,
            maxRallyFrames = maxRallyFrames,
            fixedBoldness = FixedBoldness(p1 = 0, p2 = 1),
        )
        println("   상한 $maxRallyFrames 을 걸면: 점수 ${outcome.scores.toList()} · 랠리 ${outcome.rallies} · 잘린 랠리 ${outcome.truncatedRallies}")
        assertTrue(outcome.truncatedRallies > 0, "이 조합에서 잘린 랠리가 하나도 없습니다")
    }
}
