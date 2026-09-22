package pika.env

import pika.core.GROUND_HALF_WIDTH
import pika.core.PikaPhysics
import pika.core.PikaUserInput
import pika.core.Rand

/**
 * `upstream/src/resources/js/pikavolley.js` 의 **경기 규칙만** 옮긴 것. (FR-1)
 *
 * 득점·서브권·승점 15·게임 종료가 전부다. 렌더링·페이드·메뉴·슬로모션은 옮기지 않는다.
 * 물리는 [pika.core.PikaPhysics] 를 그대로 쓴다 — Phase 1 이 동치성을 증명한 코드다.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ⚠️ 슬로모션 6프레임은 재현하지 않는다 (plan.md §4.3)
 * ─────────────────────────────────────────────────────────────────────────────
 * 업스트림은 공이 땅에 닿은 뒤에도 `slowMotionFramesLeft = 6` 동안 물리를 더 돌린다
 * (`pikavolley.js:397`). 그 6프레임에서도 공이 튀고 플레이어가 움직이며 **RNG 를 소비한다.**
 *
 * 여기서는 착지 즉시 랠리를 끝낸다. 이미 끝난 랠리를 6프레임 더 계산하는 것은 학습 가치가
 * 0 이고, 상태 공간에 의미 없는 구간을 만든다. `conformance` 의 차분 테스트 하네스도
 * 같은 규칙이라 일관된다.
 *
 * **귀결: 이 클래스는 원작의 프레임 단위 재생이 아니다.** 프레임 물리는 동치이지만
 * 랠리 경계의 프레임 수가 다르다. Phase 6 의 리플레이 뷰어는 **반드시 같은 규칙**을 써야
 * 한다 (ROADMAP Phase 2 기록 참고).
 *
 * @param rand RNG. 라운드 리셋의 `computerBoldness` 추첨과 FSM 의사결정이 소비한다.
 * @param slots 두 진영의 구성. FSM 여부가 [pika.core.Player.isComputer] 로 내려간다.
 * @param firstServeIsPlayer2 첫 서브를 오른쪽이 하는가.
 */
class PikaGame(
    private val rand: Rand,
    val slots: Slots = Slots.FSM_VS_FSM,
    val winningScore: Int = 15,
    firstServeIsPlayer2: Boolean = false,
) {
    val physics: PikaPhysics = PikaPhysics(slots.p1.isFsm, slots.p2.isFsm, rand)

    /** `[0]` player1, `[1]` player2. */
    val scores: IntArray = intArrayOf(0, 0)

    /** 다음(또는 현재) 랠리를 오른쪽이 서브하는가. */
    var isPlayer2Serve: Boolean = firstServeIsPlayer2
        private set

    var gameEnded: Boolean = false
        private set

    /** 현재 랠리에서 지난 프레임 수. 랠리 리셋에서 0 으로 돌아간다 (truncation 용). */
    var rallyFrames: Int = 0
        private set

    /** 지금까지 끝난 랠리 수. 시드 유도(plan.md §8.3) 가 이 값을 쓴다. */
    var rallyIndex: Int = 0
        private set

    init {
        // Ball 생성자는 isPlayer2Serve = false 로 초기화한다.
        // 첫 서브가 오른쪽이면 여기서 다시 잡는다. ⚠️ ball 초기화는 RNG 를 소비하지 않으므로
        // 난수 스트림이 어긋나지 않는다 (Player 두 개만 생성자에서 rand() 를 썼다).
        if (firstServeIsPlayer2) physics.ball.initializeForNewRound(true)
    }

    /**
     * 한 프레임 진행한다.
     *
     * @param inputs `[0]` player1 입력, `[1]` player2 입력.
     *   [Slot.Fsm] 슬롯의 원소는 엔진이 덮어쓰므로 무엇을 넣든 무시된다.
     * @return 이번 프레임에 랠리가 끝났다면 득점자(0 = player1, 1 = player2), 아니면 null
     */
    fun step(inputs: Array<PikaUserInput>): Int? {
        check(!gameEnded) { "게임이 이미 끝났습니다. 새 PikaGame 을 만드세요." }

        rallyFrames++
        val isBallTouchingGround = physics.runEngineForNextFrame(inputs)
        if (!isBallTouchingGround) return null

        // ⚠️ pikavolley.js:374 는 ball.x 가 아니라 ball.punchEffectX 를 읽는다.
        //    punchEffectX 는 착지 시점에 ball.x 로 세팅되는 값이고 (PhysicsEngine.kt:462),
        //    ball.x 는 그 프레임에 이미 다음 위치로 밀려 있을 수 있다.
        val scorer = if (physics.ball.punchEffectX < GROUND_HALF_WIDTH) 1 else 0
        scores[scorer]++

        // 득점한 쪽이 다음 서브 (pikavolley.js:375-394).
        isPlayer2Serve = (scorer == 1)

        if (scores[scorer] >= winningScore) {
            gameEnded = true
            // 업스트림은 state 5(win) / 6(lost) 애니메이션을 위해 이 플래그를 세운다.
            // 물리에도 영향이 있다 (이긴 쪽만 움직인다) — 관측에는 넣지 않는다 (FR-2).
            physics.player1.isWinner = (scorer == 0)
            physics.player2.isWinner = (scorer == 1)
            physics.player1.gameEnded = true
            physics.player2.gameEnded = true
        }
        return scorer
    }

    /**
     * 다음 랠리를 준비한다.
     *
     * ⚠️ **순서가 곧 RNG 소비 순서다: player1 → player2 → ball.** (plan.md §4.1)
     *    `initializeForNewRound()` 이 `rand() % 5` 로 `computerBoldness` 를 뽑기 때문에
     *    순서를 바꾸면 두 플레이어가 서로의 난수를 가져간다. 컴파일도 되고 대부분의 테스트도
     *    통과하는데 골든만 조용히 깨진다. `Harness.resetRound` (conformance) 와 같은 계약이다.
     *
     * ⚠️ 여기서 **직접 필드를 건드리지 않는다.** `divingDirection` · `lyingDownDurationLeft` ·
     *    `isWinner` · `gameEnded` · `computerWhereToStandBy` 와 공의 `expectedLandingPointX` ·
     *    `rotation` · `punchEffect*` · `previous*` 는 라운드를 넘어 살아남는다 (plan.md §4.2).
     *    "리셋이니까 전부 초기화" 로 정리하면 갈라진다.
     */
    fun startNextRally() {
        physics.player1.initializeForNewRound()
        physics.player2.initializeForNewRound()
        physics.ball.initializeForNewRound(isPlayer2Serve)
        rallyFrames = 0
        rallyIndex++
    }

    /** 이긴 쪽(0/1). 게임이 안 끝났으면 null. */
    val winner: Int?
        get() = when {
            !gameEnded -> null
            scores[0] >= winningScore -> 0
            else -> 1
        }
}
