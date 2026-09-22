package pika.env

import pika.core.BALL_RADIUS
import pika.core.BALL_TOUCHING_GROUND_Y_COORD
import pika.core.Ball
import pika.core.GROUND_HALF_WIDTH
import pika.core.GROUND_WIDTH
import pika.core.PLAYER_HALF_LENGTH
import pika.core.PLAYER_TOUCHING_GROUND_Y_COORD
import pika.core.Player

/**
 * 관측 인코더. 레이아웃은 [ObsSpec] 이 정한다. (FR-2, FR-3)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ⚠️ 여기는 핫 패스다 (plan.md §11)
 * ─────────────────────────────────────────────────────────────────────────────
 * N=256 배치에서 스텝당 512회 호출된다. float 박싱, 스텝마다 배열 할당, one-hot 을
 * `List` 로 만들기 — 이런 것이 M2-b(1M step/s) 를 날린다. **[FloatArray] 에 직접 쓴다.**
 * 할당은 0 이다.
 *
 * 참조점: 엔진만으로 8.1M step/s. (a) 가 이보다 10배 이상 느리면 범인은 여기다.
 */
class ObsEncoder(
    val opts: ObsSpec.Options = ObsSpec.Options(),
    /** 오른쪽 슬롯의 관측을 왼쪽 시점으로 뒤집는가. plan.md §5.3 — 기본 on. */
    val mirror: Boolean = true,
    /** 점수 정규화의 분모. */
    val winningScore: Int = 15,
) {
    /** 관측 차원. 기본 구성에서 40. */
    val dim: Int = ObsSpec.dim(opts)

    /** 이 레이아웃의 신원. 서버의 `Health` 와 Python 클라이언트가 대조한다. */
    val layoutHash: String = ObsSpec.layoutHash(opts)

    /**
     * 슬롯 [slotIndex] 의 시점에서 관측을 [out] 의 `[offset, offset + dim)` 에 쓴다.
     *
     * @param slotIndex 0 = player1(왼쪽), 1 = player2(오른쪽)
     */
    fun encode(game: PikaGame, slotIndex: Int, out: FloatArray, offset: Int) {
        val physics = game.physics
        val me = if (slotIndex == 0) physics.player1 else physics.player2
        val opp = if (slotIndex == 0) physics.player2 else physics.player1

        // 미러링은 오른쪽 슬롯에만 건다. 미러 후 "나" 는 언제나 왼쪽 진영에 있게 된다.
        val flip = mirror && slotIndex == 1
        val meIsLeft = if (flip) true else slotIndex == 0

        var i = offset
        i = writePlayer(me, meIsLeft, flip, out, i)
        i = writePlayer(opp, !meIsLeft, flip, out, i)
        i = writeBall(physics.ball, flip, out, i)
        i = writeMatch(game, slotIndex, out, i)
        check(i == offset + dim) { "인코더가 쓴 칸 수(${i - offset})가 dim($dim)과 다릅니다" }
    }

    private fun writePlayer(p: Player, isLeft: Boolean, flip: Boolean, out: FloatArray, start: Int): Int {
        var i = start
        val x = if (flip) GROUND_WIDTH - p.x else p.x
        out[i++] = if (isLeft) unit(x, LEFT_X_MIN, LEFT_X_MAX) else unit(x, RIGHT_X_MIN, RIGHT_X_MAX)
        out[i++] = unit(p.y, PLAYER_Y_MIN, PLAYER_Y_MAX)
        out[i++] = p.yVelocity / PLAYER_Y_VELOCITY_SCALE

        // state one-hot (7). 분기 없이 쓴다 — 조건 분기 7개보다 곱셈이 싸다.
        val s = p.state
        for (k in 0 until STATE_COUNT) out[i++] = if (s == k) 1f else 0f

        out[i++] = p.frameNumber / FRAME_NUMBER_SCALE
        out[i++] = (if (flip) -p.divingDirection else p.divingDirection).toFloat()
        out[i++] = p.lyingDownDurationLeft / LYING_DOWN_SCALE
        out[i++] = if (p.isCollisionWithBallHappened) 1f else 0f
        out[i++] = p.delayBeforeNextFrame / DELAY_SCALE
        return i
    }

    private fun writeBall(ball: Ball, flip: Boolean, out: FloatArray, start: Int): Int {
        var i = start
        out[i++] = unit(if (flip) GROUND_WIDTH - ball.x else ball.x, BALL_X_MIN, BALL_X_MAX)
        out[i++] = unit(ball.y, BALL_Y_MIN, BALL_Y_MAX)
        out[i++] = (if (flip) -ball.xVelocity else ball.xVelocity) / BALL_VELOCITY_SCALE
        out[i++] = ball.yVelocity / BALL_VELOCITY_SCALE
        out[i++] = if (ball.isPowerHit) 1f else 0f
        if (opts.includeExpectedLanding) {
            val e = if (flip) GROUND_WIDTH - ball.expectedLandingPointX else ball.expectedLandingPointX
            out[i++] = unit(e, BALL_X_MIN, BALL_X_MAX)
        }
        return i
    }

    private fun writeMatch(game: PikaGame, slotIndex: Int, out: FloatArray, start: Int): Int {
        var i = start
        val mine = game.scores[slotIndex]
        val theirs = game.scores[1 - slotIndex]
        val denom = winningScore.toFloat()
        out[i++] = mine / denom
        out[i++] = theirs / denom
        out[i++] = (mine - theirs) / denom
        out[i++] = if (game.isPlayer2Serve == (slotIndex == 1)) 1f else 0f
        // ⚠️ 진영 플래그는 미러링하지 않는다. 미러링이 지우는 정보를 되살리는 것이 목적이다.
        if (opts.includeSideFlag) out[i++] = if (slotIndex == 0) -1f else 1f
        return i
    }

    companion object {
        /**
         * `[lo, hi]` → `[-1, 1]`.
         *
         * ⚠️ 자르지 않는다. 범위는 척도이지 상한이 아니다 — 자르면 "아주 빠른 공" 과
         *    "빠른 공" 이 구별되지 않는다. .proto 주석에 같은 문장이 있다.
         */
        fun unit(v: Int, lo: Int, hi: Int): Float = 2f * (v - lo) / (hi - lo) - 1f

        // ── 정규화 상수 ──────────────────────────────────────────────────
        // ⚠️ 이 숫자들은 proto/obs_spec.proto 주석에 **그대로** 적혀 있다.
        //    장래의 JS 구현이 Kotlin 을 읽지 않고도 같은 관측을 만들 수 있어야 한다 (plan.md §12.3).
        //    여기를 바꾸면 .proto 주석도 바꾼다.

        /** player1 의 x 가동 범위 [32, 184]. */
        const val LEFT_X_MIN = PLAYER_HALF_LENGTH                        // 32
        const val LEFT_X_MAX = GROUND_HALF_WIDTH - PLAYER_HALF_LENGTH    // 184

        /** player2 의 x 가동 범위 [248, 400]. 216 대칭이다 — 비대칭인 쪽은 공이다. */
        const val RIGHT_X_MIN = GROUND_HALF_WIDTH + PLAYER_HALF_LENGTH   // 248
        const val RIGHT_X_MAX = GROUND_WIDTH - PLAYER_HALF_LENGTH        // 400

        /** 점프 최고점. 244 - (1+2+...+16) = 108. */
        const val PLAYER_Y_MIN = PLAYER_TOUCHING_GROUND_Y_COORD - 136    // 108
        const val PLAYER_Y_MAX = PLAYER_TOUCHING_GROUND_Y_COORD          // 244

        const val PLAYER_Y_VELOCITY_SCALE = 16f
        const val FRAME_NUMBER_SCALE = 5f
        const val LYING_DOWN_SCALE = 3f
        const val DELAY_SCALE = 5f

        /** 공의 가동 폭 [20, 432]. 중심이 226 이라 네트(216)와 어긋난다 (plan.md §2.2). */
        const val BALL_X_MIN = BALL_RADIUS                                // 20
        const val BALL_X_MAX = GROUND_WIDTH                               // 432
        const val BALL_Y_MIN = 0
        const val BALL_Y_MAX = BALL_TOUCHING_GROUND_Y_COORD               // 252
        const val BALL_VELOCITY_SCALE = 20f

        /** `Player.state` 의 가짓수 (0..6). */
        const val STATE_COUNT = 7
    }
}
