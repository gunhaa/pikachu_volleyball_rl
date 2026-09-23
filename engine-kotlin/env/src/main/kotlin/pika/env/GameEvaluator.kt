package pika.env

import pika.core.PikaUserInput
import pika.core.Rand
import pika.core.XorShift32

/**
 * 15점제 게임 단위의 평가. (FR-13, plan.md §6.3)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 왜 보상 합계가 아니라 이것이 따로 있는가
 * ─────────────────────────────────────────────────────────────────────────────
 * `rallyWin` 합계는 **학습 신호**이고, ROADMAP 의 완료 조건은 **게임 승률**이다.
 * 둘은 같은 것이 아니다 — 랠리 승률이 같아도 서브권 연쇄 때문에 게임 승률은 갈린다.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ⚠️ 평가는 **반드시 양 진영에서** 돌린다. 이것은 설정이 아니라 규칙이다 (FR-13).
 * ─────────────────────────────────────────────────────────────────────────────
 * 이 게임은 좌우 대칭이 아니다 (plan.md §2.2 — 공의 가동 폭 [20,432] 의 중심은 226,
 * 네트는 216). FSM 끼리 붙이면 왼쪽이 800게임 중 799 를 이긴다. 한 진영에서만 재면
 * 승률이 실력이 아니라 **진영**을 재게 된다.
 *
 * [EvalResult.sideGap] 이 그 교락(confounding) 의 크기를 눈에 보이게 만든다.
 */
object GameEvaluator {

    /** 한 진영에서의 성적. */
    data class WinRate(
        val wins: Int,
        val games: Int,
        val pointsFor: Int,
        val pointsAgainst: Int,
        val rallies: Int,
        val frames: Long,
        val truncatedRallies: Int,
    ) {
        val rate: Double get() = if (games == 0) 0.0 else wins.toDouble() / games
        val meanRallyFrames: Double get() = if (rallies == 0) 0.0 else frames.toDouble() / rallies

        override fun toString(): String =
            "%d/%d (%.4f) 득점 %d:%d, 랠리 %d, 평균 %.1f프레임".format(
                wins, games, rate, pointsFor, pointsAgainst, rallies, meanRallyFrames,
            )
    }

    /** 양 진영 결과. */
    data class EvalResult(val asLeft: WinRate, val asRight: WinRate) {
        /** 양 진영 평균 승률. 단일 숫자가 필요한 곳은 이것을 쓴다. */
        val combined: Double get() = (asLeft.rate + asRight.rate) / 2

        /**
         * plan.md §2.2 의 진영 효과 크기. 0 에서 멀수록 정책이 진영에 의존한다.
         * FSM 을 정책 자리에 넣으면 ≈ +1.0 이 나온다 (그것이 M2-e 다).
         */
        val sideGap: Double get() = asLeft.rate - asRight.rate

        override fun toString(): String =
            "왼쪽 $asLeft\n오른쪽 $asRight\n합산 %.4f, 진영차 %+.4f".format(combined, sideGap)
    }

    /** 게임 하나의 결과. */
    data class GameOutcome(
        val winner: Int,
        val scores: IntArray,
        val rallies: Int,
        val frames: Long,
        val truncatedRallies: Int,
    ) {
        // data class + IntArray 의 equals/hashCode 경고를 피하기 위해 직접 구현한다.
        override fun equals(other: Any?): Boolean =
            other is GameOutcome && winner == other.winner && scores.contentEquals(other.scores) &&
                rallies == other.rallies && frames == other.frames &&
                truncatedRallies == other.truncatedRallies

        override fun hashCode(): Int = winner * 31 + scores.contentHashCode()
    }

    /**
     * 게임 하나를 끝까지 돌린다.
     *
     * @param maxRallyFrames 0 이면 무제한. 양수면 그 프레임을 넘긴 랠리는 **득점 없이 버린다**
     *   (양쪽 다 잘해져서 안 끝나는 랠리 대비 — PRD §7). 버려진 랠리는 따로 센다.
     * @param maxGameFrames 안전장치. 넘으면 예외다. 평가가 조용히 멈추는 것보다 낫다.
     * @param fixedBoldness FSM 의 boldness 고정값. **진영별로 다르게 줄 수 있다** —
     *   `plan.md` §2.1 측정 2 의 FSM(b_left) × FSM(b_right) 행렬이 그것을 요구한다 (FR-13).
     */
    fun playGame(
        seed: Int,
        slots: Slots,
        p1: Controller? = null,
        p2: Controller? = null,
        winningScore: Int = 15,
        firstServeIsPlayer2: Boolean = false,
        maxRallyFrames: Int = 0,
        maxGameFrames: Long = 2_000_000,
        fixedBoldness: FixedBoldness = FixedBoldness.RANDOM,
    ): GameOutcome {
        requireController(slots.p1, p1, "player1")
        requireController(slots.p2, p2, "player2")

        val rng = XorShift32(seed)
        val game = PikaGame(Rand { rng.nextRand() }, slots, winningScore, firstServeIsPlayer2, fixedBoldness)
        val inputs = arrayOf(PikaUserInput(), PikaUserInput())

        var frames = 0L
        var rallies = 0
        var truncated = 0

        while (!game.gameEnded) {
            // FSM 슬롯의 입력은 엔진이 덮어쓴다 (PhysicsEngine.kt:129). 채워도 버려진다.
            if (!slots.p1.isFsm) p1!!.decide(game, false, inputs[0])
            if (!slots.p2.isFsm) p2!!.decide(game, true, inputs[1])

            val scorer = game.step(inputs)
            frames++
            check(frames <= maxGameFrames) {
                "게임이 ${maxGameFrames} 프레임 안에 끝나지 않았습니다 " +
                    "(seed=$seed, $fixedBoldness, 점수=${game.scores.toList()}, 랠리 ${game.rallyFrames}프레임)"
            }

            if (scorer != null) {
                rallies++
                if (!game.gameEnded) game.startNextRally()
            } else if (maxRallyFrames > 0 && game.rallyFrames >= maxRallyFrames) {
                truncated++
                rallies++
                game.startNextRally()
            }
        }
        return GameOutcome(game.winner!!, game.scores.copyOf(), rallies, frames, truncated)
    }

    /**
     * 같은 정책을 **양 진영에서** 돌린다.
     *
     * 게임 i 는 시드 `baseSeed + i / 2`, 첫 서브는 `i % 2 == 1` 이면 오른쪽이다.
     * 즉 시드 하나당 첫 서브 두 가지를 모두 본다 — 첫 서브가 결과를 정하지 않음을
     * 데이터가 말하게 하기 위해서다 (plan.md §2.2).
     *
     * @param policy 평가 대상. null 이면 엔진 내장 FSM 을 그 자리에 넣는다 (M2-e 의 자기 검증).
     * @param opponent 상대. null 이면 FSM.
     * @param fixedBoldness FSM 의 boldness 고정값 (진단 축, FR-13). 진영을 바꿔도 **같은 값**을
     *   쓴다 — 양쪽에 걸리지만 External 슬롯에서는 읽히지 않으므로 FSM 이 어느 쪽에 있든 같다.
     */
    fun evaluate(
        policy: Controller? = null,
        opponent: Controller? = null,
        games: Int = 800,
        baseSeed: Int = 0,
        winningScore: Int = 15,
        maxRallyFrames: Int = 0,
        fixedBoldness: FixedBoldness = FixedBoldness.RANDOM,
        onGame: (side: Int, index: Int, GameOutcome) -> Unit = { _, _, _ -> },
    ): EvalResult {
        val policySlot = if (policy == null) Slot.Fsm else Slot.External
        val opponentSlot = if (opponent == null) Slot.Fsm else Slot.External

        fun side(policyIsPlayer2: Boolean): WinRate {
            var wins = 0
            var pointsFor = 0
            var pointsAgainst = 0
            var rallies = 0
            var frames = 0L
            var truncated = 0
            val policyIdx = if (policyIsPlayer2) 1 else 0

            for (i in 0 until games) {
                val outcome = playGame(
                    seed = baseSeed + i / 2,
                    slots = if (policyIsPlayer2) Slots(opponentSlot, policySlot) else Slots(policySlot, opponentSlot),
                    p1 = if (policyIsPlayer2) opponent else policy,
                    p2 = if (policyIsPlayer2) policy else opponent,
                    winningScore = winningScore,
                    firstServeIsPlayer2 = i % 2 == 1,
                    maxRallyFrames = maxRallyFrames,
                    fixedBoldness = fixedBoldness,
                )
                if (outcome.winner == policyIdx) wins++
                pointsFor += outcome.scores[policyIdx]
                pointsAgainst += outcome.scores[1 - policyIdx]
                rallies += outcome.rallies
                frames += outcome.frames
                truncated += outcome.truncatedRallies
                onGame(policyIdx, i, outcome)
            }
            return WinRate(wins, games, pointsFor, pointsAgainst, rallies, frames, truncated)
        }

        return EvalResult(asLeft = side(policyIsPlayer2 = false), asRight = side(policyIsPlayer2 = true))
    }

    private fun requireController(slot: Slot, controller: Controller?, who: String) {
        require(slot.isFsm == (controller == null)) {
            if (slot.isFsm) "$who 는 FSM 슬롯인데 Controller 가 주어졌습니다 — 엔진이 덮어쓰므로 무시됩니다"
            else "$who 는 External 슬롯인데 Controller 가 없습니다"
        }
    }
}
