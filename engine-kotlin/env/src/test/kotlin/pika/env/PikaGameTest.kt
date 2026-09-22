package pika.env

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import pika.core.GROUND_WIDTH
import pika.core.PikaUserInput
import pika.core.Rand
import pika.core.XorShift32

/** 경기 규칙 포팅의 계약. (tasks.md P2, FR-1) */
class PikaGameTest {

    /** 0, 1, 2, ... 를 차례로 내는 RNG. 소비 **순서**가 값으로 드러나게 만든다. */
    private class CountingRand : Rand {
        var calls = 0
            private set

        override fun next(): Int = calls++ % 32768
    }

    @Test
    @DisplayName("라운드 리셋의 RNG 소비 순서는 player1 → player2 → ball 이다")
    fun resetOrderIsContract() {
        val rand = CountingRand()
        // External 끼리라 FSM 이 RNG 를 소비하지 않는다. 소비자는 initializeForNewRound 뿐이다.
        val game = PikaGame(rand, Slots.EXTERNAL_VS_EXTERNAL)

        // 생성자: player1(0) → player2(1). ball 생성자는 RNG 를 소비하지 않는다.
        assertEquals(2, rand.calls, "생성 시 rand() 소비 횟수 = 플레이어 2명")
        assertEquals(0 % 5, game.physics.player1.computerBoldness)
        assertEquals(1 % 5, game.physics.player2.computerBoldness)

        game.startNextRally()

        // ⚠️ 이 두 줄이 리셋 순서를 못 박는다. player2 → player1 로 바꾸면 2 와 3 이 뒤바뀐다.
        assertEquals(2 % 5, game.physics.player1.computerBoldness, "player1 이 먼저 뽑는다")
        assertEquals(3 % 5, game.physics.player2.computerBoldness, "player2 가 그 다음이다")
        assertEquals(4, rand.calls, "리셋 1회 = rand() 2회. ball 은 소비하지 않는다")
    }

    @Test
    @DisplayName("서브권이 공의 초기 위치를 정한다 — ball 리셋은 플레이어 뒤에 온다")
    fun serveSideDecidesBallPosition() {
        val left = PikaGame(CountingRand(), Slots.EXTERNAL_VS_EXTERNAL, firstServeIsPlayer2 = false)
        assertEquals(56, left.physics.ball.x)

        val right = PikaGame(CountingRand(), Slots.EXTERNAL_VS_EXTERNAL, firstServeIsPlayer2 = true)
        assertEquals(GROUND_WIDTH - 56, right.physics.ball.x)
        // 첫 서브를 바꿔도 RNG 소비는 같다 — ball 초기화는 난수를 쓰지 않는다.
        assertEquals(left.physics.player2.computerBoldness, right.physics.player2.computerBoldness)
    }

    @Test
    @DisplayName("승점에 도달하면 gameEnded 와 isWinner 가 양쪽 플레이어에 세팅된다")
    fun reachingWinningScoreEndsGame() {
        val rng = XorShift32(7)
        val game = PikaGame(Rand { rng.nextRand() }, Slots.FSM_VS_FSM, winningScore = 1)
        val inputs = arrayOf(PikaUserInput(), PikaUserInput())

        var scorer: Int? = null
        var frames = 0
        while (scorer == null) {
            scorer = game.step(inputs)
            check(++frames < 100_000) { "랠리가 끝나지 않았습니다" }
        }

        assertTrue(game.gameEnded, "승점 도달 시 gameEnded")
        assertEquals(scorer, game.winner)
        assertEquals(1, game.scores[scorer])
        // 업스트림은 두 플레이어 모두에게 플래그를 세운다 (이긴 쪽은 win, 진 쪽은 lost 애니메이션).
        assertTrue(game.physics.player1.gameEnded && game.physics.player2.gameEnded)
        assertEquals(scorer == 0, game.physics.player1.isWinner)
        assertEquals(scorer == 1, game.physics.player2.isWinner)
    }

    @Test
    @DisplayName("득점자가 다음 서브를 갖는다")
    fun scorerServesNext() {
        val rng = XorShift32(11)
        val game = PikaGame(Rand { rng.nextRand() }, Slots.FSM_VS_FSM, winningScore = 15)
        val inputs = arrayOf(PikaUserInput(), PikaUserInput())

        repeat(5) {
            var scorer: Int? = null
            while (scorer == null) scorer = game.step(inputs)
            assertEquals(scorer == 1, game.isPlayer2Serve, "득점자가 서브권을 가져간다")
            game.startNextRally()
            assertEquals(if (scorer == 1) GROUND_WIDTH - 56 else 56, game.physics.ball.x)
        }
    }

    @Test
    @DisplayName("rallyFrames 는 랠리마다 0 에서 다시 센다")
    fun rallyFramesResetPerRally() {
        val rng = XorShift32(3)
        val game = PikaGame(Rand { rng.nextRand() }, Slots.FSM_VS_FSM)
        val inputs = arrayOf(PikaUserInput(), PikaUserInput())

        assertEquals(0, game.rallyFrames)
        game.step(inputs)
        assertEquals(1, game.rallyFrames)

        var scorer: Int? = null
        while (scorer == null) scorer = game.step(inputs)
        assertTrue(game.rallyFrames > 1)
        game.startNextRally()
        assertEquals(0, game.rallyFrames)
        assertEquals(1, game.rallyIndex)
    }

    @Test
    @DisplayName("끝난 게임을 더 진행시키면 실패한다 — 조용히 이어지지 않는다")
    fun steppingEndedGameFails() {
        val rng = XorShift32(5)
        val game = PikaGame(Rand { rng.nextRand() }, Slots.FSM_VS_FSM, winningScore = 1)
        val inputs = arrayOf(PikaUserInput(), PikaUserInput())
        while (!game.gameEnded) game.step(inputs)

        assertThrows(IllegalStateException::class.java) { game.step(inputs) }
    }

    @Test
    @DisplayName("External 슬롯은 isComputer = false 다 — FSM 분기에 들어갈 수 없다 (M2-g 의 바닥)")
    fun externalSlotsAreNotComputers() {
        val game = PikaGame(CountingRand(), Slots.EXTERNAL_VS_EXTERNAL)
        assertFalse(game.physics.player1.isComputer)
        assertFalse(game.physics.player2.isComputer)
        assertFalse(game.slots.usesFsm)

        val mixed = PikaGame(CountingRand(), Slots.EXTERNAL_VS_FSM)
        assertFalse(mixed.physics.player1.isComputer)
        assertTrue(mixed.physics.player2.isComputer)
        assertTrue(mixed.slots.usesFsm)
    }
}
