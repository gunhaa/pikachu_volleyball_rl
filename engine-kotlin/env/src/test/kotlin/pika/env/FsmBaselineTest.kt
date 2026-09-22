package pika.env

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import pika.core.PikaUserInput
import pika.core.Rand
import pika.core.XorShift32

/**
 * M2-e — FSM vs FSM 베이스라인 재현. (PRD §4, plan.md §2.2)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 이 테스트가 증명하는 것
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. [PikaGame] 이 `pikavolley.js` 의 경기 규칙과 같은 결과를 낸다 (득점 판정·서브권·15점).
 * 2. **좌우 비대칭이 실재한다** — 800 게임에서 왼쪽이 799 를 이긴다. 진영을 교락변수로
 *    다뤄야 하는 이유이고, [GameEvaluator] 가 양 진영을 강제하는 이유다 (FR-13).
 *
 * ⚠️ 이 숫자가 바뀌면 물리나 경기 규칙이 바뀐 것이다. 기대값을 고치기 전에 원인을 찾아라.
 *    (`conformance` 의 골든 615 에피소드가 초록인데 여기가 빨간불이면 범인은 `env` 다.)
 */
class FsmBaselineTest {

    private data class Baseline(
        val p1Points: Int,
        val p2Points: Int,
        val p1WinsFirstServeP1: Int,
        val p1WinsFirstServeP2: Int,
        val rallies: Int,
        /** `[서브한 쪽][득점한 쪽]` */
        val serveToScore: Array<IntArray>,
        val frames: Long,
    )

    /** 시드 0..399 × 첫 서브 2가지 = 800 게임. */
    private fun measure(seeds: IntRange = 0..399): Baseline {
        var p1Points = 0
        var p2Points = 0
        var winsFsP1 = 0
        var winsFsP2 = 0
        var rallies = 0
        var frames = 0L
        val serveToScore = Array(2) { IntArray(2) }
        val inputs = arrayOf(PikaUserInput(), PikaUserInput())

        for (seed in seeds) {
            for (firstServeIsPlayer2 in listOf(false, true)) {
                val rng = XorShift32(seed)
                val game = PikaGame(Rand { rng.nextRand() }, Slots.FSM_VS_FSM, 15, firstServeIsPlayer2)

                while (!game.gameEnded) {
                    val server = if (game.isPlayer2Serve) 1 else 0
                    val scorer = game.step(inputs)
                    frames++
                    if (scorer != null) {
                        rallies++
                        serveToScore[server][scorer]++
                        if (!game.gameEnded) game.startNextRally()
                    }
                }

                p1Points += game.scores[0]
                p2Points += game.scores[1]
                if (game.winner == 0) {
                    if (firstServeIsPlayer2) winsFsP2++ else winsFsP1++
                }
            }
        }
        return Baseline(p1Points, p2Points, winsFsP1, winsFsP2, rallies, serveToScore, frames)
    }

    @Test
    @DisplayName("M2-e: FSM vs FSM 800 게임 — p1 승 799/800, 득점 11,998 : 4,169")
    fun fsmBaseline() {
        val b = measure()

        println(
            """
            |── M2-e FSM vs FSM 베이스라인 (시드 0..399 × 첫서브 2가지 = 800 게임) ──
            |  득점            p1 ${b.p1Points} : p2 ${b.p2Points}
            |  p1 게임 승리     첫서브 p1 일 때 ${b.p1WinsFirstServeP1}/400 · 첫서브 p2 일 때 ${b.p1WinsFirstServeP2}/400
            |  랠리            ${b.rallies} (총 ${b.frames} 프레임, 평균 ${b.frames / b.rallies} 프레임)
            |  서브→득점       p1서브→p1 ${b.serveToScore[0][0]} / p1서브→p2 ${b.serveToScore[0][1]}
            |                  p2서브→p1 ${b.serveToScore[1][0]} / p2서브→p2 ${b.serveToScore[1][1]}
            """.trimMargin(),
        )

        assertEquals(11_998, b.p1Points, "p1 총 득점 (plan.md §2.2)")
        assertEquals(4_169, b.p2Points, "p2 총 득점 (plan.md §2.2)")
        assertEquals(399, b.p1WinsFirstServeP1, "첫 서브가 p1 일 때 p1 승")
        assertEquals(400, b.p1WinsFirstServeP2, "첫 서브가 p2 일 때 p1 승 — 첫 서브는 결과를 바꾸지 않는다")
        assertEquals(8404, b.serveToScore[0][0], "p1서브 → p1득점")
        assertEquals(3195, b.serveToScore[0][1], "p1서브 → p2득점")
        assertEquals(3594, b.serveToScore[1][0], "p2서브 → p1득점")
        assertEquals(974, b.serveToScore[1][1], "p2서브 → p2득점")
        assertEquals(b.p1Points + b.p2Points, b.rallies, "랠리 수 = 총 득점 수 (truncation 없음)")
    }

    @Test
    @DisplayName("GameEvaluator 자기 검증: 정책 자리에 FSM 을 넣으면 왼쪽 ≈ 1.00 · 오른쪽 ≈ 0.00")
    fun evaluatorSelfCheck() {
        // 정책도 상대도 null(=FSM) 이다. 즉 같은 게임을 진영만 바꿔 읽는다.
        val result = GameEvaluator.evaluate(policy = null, opponent = null, games = 800, baseSeed = 0)
        println("── GameEvaluator 자기 검증 ──\n$result")

        assertEquals(799, result.asLeft.wins, "왼쪽 진영 승 — M2-e")
        assertEquals(1, result.asRight.wins, "오른쪽 진영 승")
        assertEquals(800, result.asLeft.wins + result.asRight.wins, "같은 게임을 양쪽에서 읽었으므로 합이 게임 수다")
        assertEquals(11_998, result.asLeft.pointsFor, "왼쪽 득점")
        assertEquals(4_169, result.asRight.pointsFor, "오른쪽 득점")
        // 진영차가 거의 1 이다. 이것이 "한 진영에서만 재면 안 되는" 이유다.
        assertEquals(0.9975, result.sideGap, 1e-9)
    }
}
