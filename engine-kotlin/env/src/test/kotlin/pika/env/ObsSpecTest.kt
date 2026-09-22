package pika.env

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import pika.core.PikaUserInput
import pika.core.Rand
import pika.core.XorShift32

/** 관측 레이아웃의 계약. (tasks.md P3, NFR-5, FR-2) */
class ObsSpecTest {

    @Test
    @DisplayName("ObsSpec.kt 의 필드 순서가 proto/obs_spec.proto 와 같다 (플래그 4조합 전부)")
    fun matchesProto() {
        ObsSpec.assertMatchesProto()
    }

    @Test
    @DisplayName("차원: 기본 40, 착지점 제외 39, 진영 플래그 포함 41")
    fun dimensions() {
        assertEquals(40, ObsSpec.dim(ObsSpec.Options()))
        assertEquals(39, ObsSpec.dim(ObsSpec.Options(includeExpectedLanding = false)))
        assertEquals(41, ObsSpec.dim(ObsSpec.Options(includeSideFlag = true)))
        assertEquals(40, ObsSpec.fieldNames().size)
        assertEquals(15, ObsSpec.playerFieldNames.size)
    }

    @Test
    @DisplayName("레이아웃 해시는 플래그마다 다르다 — 어긋난 서버에 붙으면 즉시 실패해야 한다")
    fun layoutHashIdentifiesLayout() {
        val base = ObsSpec.layoutHash(ObsSpec.Options())
        assertEquals(64, base.length)
        assertEquals(base, ObsSpec.layoutHash(ObsSpec.Options()), "같은 구성 → 같은 해시")
        assertNotEquals(base, ObsSpec.layoutHash(ObsSpec.Options(includeExpectedLanding = false)))
        assertNotEquals(base, ObsSpec.layoutHash(ObsSpec.Options(includeSideFlag = true)))
    }

    @Test
    @DisplayName("관측 어디에도 상대의 은닉 상태가 없다 — computerBoldness 를 바꿔도 관측이 불변이다")
    fun observationCarriesNoHiddenState() {
        // FR-2 의 핵심. 필드 목록을 눈으로 훑는 대신 **행동으로** 확인한다.
        val config = EnvConfig(slots = Slots.EXTERNAL_VS_FSM, baseSeed = 42)
        val encoder = ObsEncoder(config.obs, config.mirrorObservations, config.winningScore)

        val reference = FloatArray(encoder.dim)
        for (boldness in 0..4) {
            val rng = XorShift32(7)
            val game = PikaGame(Rand { rng.nextRand() }, Slots.EXTERNAL_VS_FSM)
            // 상대(FSM)의 은닉 상태를 직접 바꾼다.
            game.physics.player2.computerBoldness = boldness
            game.physics.player2.computerWhereToStandBy = boldness % 2

            val obs = FloatArray(encoder.dim)
            encoder.encode(game, 0, obs, 0)
            if (boldness == 0) {
                obs.copyInto(reference)
            } else {
                assertTrue(
                    reference.contentEquals(obs),
                    "computerBoldness=$boldness 에서 관측이 달라졌습니다 — 은닉 정보가 새고 있습니다",
                )
            }
        }
    }

    @Test
    @DisplayName("미러링 on 이면 대칭 상태에서 두 슬롯의 관측이 같다")
    fun mirroringMakesSymmetricStatesIdentical() {
        val encoder = ObsEncoder(ObsSpec.Options(), mirror = true, winningScore = 15)
        val rng = XorShift32(3)
        val game = PikaGame(Rand { rng.nextRand() }, Slots.EXTERNAL_VS_EXTERNAL)
        val p = game.physics

        // 네트(216)를 축으로 완전히 대칭인 상태를 손으로 만든다.
        p.player1.x = 100; p.player2.x = 332          // 432 - 100
        p.player1.y = 200; p.player2.y = 200
        p.player1.yVelocity = -4; p.player2.yVelocity = -4
        p.player1.state = 1; p.player2.state = 1
        p.player1.frameNumber = 2; p.player2.frameNumber = 2
        p.player1.divingDirection = 1; p.player2.divingDirection = -1
        p.player1.lyingDownDurationLeft = 2; p.player2.lyingDownDurationLeft = 2
        p.player1.delayBeforeNextFrame = 1; p.player2.delayBeforeNextFrame = 1
        p.ball.x = 216; p.ball.y = 100                // 미러 축 위의 공
        p.ball.xVelocity = 0; p.ball.yVelocity = 5
        p.ball.expectedLandingPointX = 216

        val left = FloatArray(encoder.dim)
        val right = FloatArray(encoder.dim)
        encoder.encode(game, 0, left, 0)
        encoder.encode(game, 1, right, 0)

        // ⚠️ ball.x 는 216 이고 미러는 432-216 = 216 이라 같다. 공의 가동 폭 중심이 226 이라
        //    **216 이 아닌 x 에서는 정규화 값이 어긋난다** — 그것이 plan.md §2.2 의 비대칭이다.
        //
        // ⚠️ match.i_am_serving 은 제외한다. 서브권은 한쪽만 갖는 것이라 **대칭일 수 없다.**
        //    위치의 대칭과 경기 상황의 대칭은 다른 것이고, 미러링이 맞추는 것은 전자뿐이다.
        val names = ObsSpec.fieldNames()
        val serveIdx = names.indexOf("match.i_am_serving")
        for (i in 0 until encoder.dim) {
            if (i == serveIdx) continue
            assertEquals(left[i], right[i], 1e-6f, "미러링 후 ${names[i]} 가 다릅니다")
        }
        assertNotEquals(left[serveIdx], right[serveIdx], "서브권은 두 슬롯이 나눠 가진다")
        assertEquals(1f, left[serveIdx] + right[serveIdx], "서브는 언제나 정확히 한쪽이다")
    }

    @Test
    @DisplayName("미러링 off 면 두 슬롯의 관측이 다르다 — 미러링이 실제로 무언가를 한다")
    fun mirroringOffKeepsSidesDistinct() {
        val encoder = ObsEncoder(ObsSpec.Options(), mirror = false, winningScore = 15)
        val rng = XorShift32(3)
        val game = PikaGame(Rand { rng.nextRand() }, Slots.EXTERNAL_VS_EXTERNAL)
        val left = FloatArray(encoder.dim)
        val right = FloatArray(encoder.dim)
        encoder.encode(game, 0, left, 0)
        encoder.encode(game, 1, right, 0)
        assertTrue(!left.contentEquals(right), "미러링 off 인데 두 진영의 관측이 같습니다")
    }

    @Test
    @DisplayName("진영 플래그는 미러링되지 않는다 — 미러링이 지운 정보를 되살리는 것이 목적이다")
    fun sideFlagSurvivesMirroring() {
        val opts = ObsSpec.Options(includeSideFlag = true)
        val encoder = ObsEncoder(opts, mirror = true, winningScore = 15)
        val rng = XorShift32(3)
        val game = PikaGame(Rand { rng.nextRand() }, Slots.EXTERNAL_VS_EXTERNAL)
        val obs = FloatArray(encoder.dim)
        val idx = ObsSpec.fieldNames(opts).indexOf("match.side_flag")
        assertEquals(encoder.dim - 1, idx)

        encoder.encode(game, 0, obs, 0)
        assertEquals(-1f, obs[idx])
        encoder.encode(game, 1, obs, 0)
        assertEquals(1f, obs[idx])
    }

    @Test
    @DisplayName("정규화: 진영 양 끝이 -1 과 +1 로 간다")
    fun normalizationEndpoints() {
        assertEquals(-1f, ObsEncoder.unit(ObsEncoder.LEFT_X_MIN, ObsEncoder.LEFT_X_MIN, ObsEncoder.LEFT_X_MAX))
        assertEquals(1f, ObsEncoder.unit(ObsEncoder.LEFT_X_MAX, ObsEncoder.LEFT_X_MIN, ObsEncoder.LEFT_X_MAX))
        assertEquals(0f, ObsEncoder.unit(108, ObsEncoder.LEFT_X_MIN, ObsEncoder.LEFT_X_MAX))
        // 범위 밖은 자르지 않는다.
        assertTrue(ObsEncoder.unit(500, ObsEncoder.BALL_X_MIN, ObsEncoder.BALL_X_MAX) > 1f)
    }

    @Test
    @DisplayName("인코더는 점수를 슬롯 시점으로 쓴다 (내 점수가 먼저)")
    fun scoresAreEgocentric() {
        val encoder = ObsEncoder()
        val rng = XorShift32(1)
        val game = PikaGame(Rand { rng.nextRand() }, Slots.EXTERNAL_VS_EXTERNAL)
        val inputs = arrayOf(PikaUserInput(), PikaUserInput())
        // 점수를 만들기 위해 랠리 하나를 끝까지 돌린다.
        var scorer: Int? = null
        while (scorer == null) scorer = game.step(inputs)

        val names = ObsSpec.fieldNames()
        val mine = names.indexOf("match.my_score")
        val theirs = names.indexOf("match.opponent_score")
        val obs = FloatArray(encoder.dim)

        encoder.encode(game, scorer, obs, 0)
        assertEquals(1f / 15f, obs[mine], 1e-6f, "득점한 슬롯의 my_score")
        assertEquals(0f, obs[theirs], 1e-6f)

        encoder.encode(game, 1 - scorer, obs, 0)
        assertEquals(0f, obs[mine], 1e-6f, "실점한 슬롯의 my_score")
        assertEquals(1f / 15f, obs[theirs], 1e-6f)
    }
}
