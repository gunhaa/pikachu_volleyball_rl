package pika.conformance

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * 하네스의 라운드 리셋이 JS 와 동일한지 본다. (tasks.md P5)
 *
 * 하네스 버그를 물리 불일치로 오인하지 않기 위한 테스트다.
 * `initializeForNewRound` 은 `physicsEngine` 과 독립이므로 **엔진 포팅 전에도 검증된다.**
 *
 * 여기서 실제로 확인되는 것
 *   - 생성자의 RNG 소비 (player1 → player2, 각 1회)
 *   - 리셋 순서 player1 → player2 → ball
 *   - `computerBoldness = rand() % 5` 의 재추첨 타이밍
 *   - 리셋 대상이 **아닌** 5개 필드가 라운드를 건너 살아남는다는 점
 *   - State Spec 직렬화와 해시가 양쪽에서 같은 결과를 낸다는 점
 */
class RoundResetParityTest {

    @BeforeEach
    fun requireUpstream() {
        assumeTrue(RepoPaths.upstreamIsPresent(), "upstream/ 이 없습니다 (scripts/fetch-upstream.sh)")
    }

    // TARGETED 는 시드 축이 다르다 (시드 = 케이스 번호, 프레임 수도 케이스가 정한다).
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Generator::class, names = ["TARGETED"], mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("라운드 리셋 50시드 × 20회가 JS 와 완전히 일치한다")
    fun resetParity(gen: Generator) {
        val result = Lockstep(frames = 20, gen = gen, probeResets = true).run("1..50")
        assertTrue(
            result.allMatched,
            "라운드 리셋이 JS 와 갈라졌습니다: ${result.mismatch}\n" +
                (result.mismatch?.let { Drilldowns.of(it).report() } ?: ""),
        )
    }

    @Test
    @DisplayName("엄격 모드(sound 포함)에서도 일치한다")
    fun resetParityStrict() {
        val result = Lockstep(frames = 20, gen = Generator.UNIFORM, strict = true, probeResets = true)
            .run("1..20")
        assertTrue(result.allMatched, "엄격 모드 불일치: ${result.mismatch}")
    }

    @Test
    @DisplayName("리셋 대상이 아닌 필드는 라운드를 건너 살아남는다")
    fun nonResetFieldsSurviveRounds() {
        val rng = XorShift32(1)
        val physics = pika.core.PikaPhysics(false, false, pika.core.Rand { rng.nextRand() })

        // 리셋 대상이 아닌 필드를 일부러 오염시킨다.
        physics.player1.divingDirection = 1
        physics.player1.lyingDownDurationLeft = 7
        physics.player1.computerWhereToStandBy = 1
        physics.player1.isWinner = true
        physics.player1.gameEnded = true
        physics.ball.expectedLandingPointX = 123
        physics.ball.rotation = 5

        Harness.resetRound(physics, isPlayer2Serve = true)

        assertAll(
            { assertTrue(physics.player1.divingDirection == 1, "divingDirection 이 리셋되었다") },
            { assertTrue(physics.player1.lyingDownDurationLeft == 7, "lyingDownDurationLeft 가 리셋되었다") },
            { assertTrue(physics.player1.computerWhereToStandBy == 1, "computerWhereToStandBy 가 리셋되었다") },
            { assertTrue(physics.player1.isWinner, "isWinner 가 리셋되었다") },
            { assertTrue(physics.player1.gameEnded, "gameEnded 가 리셋되었다") },
            { assertTrue(physics.ball.expectedLandingPointX == 123, "expectedLandingPointX 가 리셋되었다") },
            { assertTrue(physics.ball.rotation == 5, "rotation 이 리셋되었다") },
            // 리셋 대상은 실제로 리셋되어야 한다.
            { assertTrue(physics.player1.x == 36, "player1.x 가 리셋되지 않았다") },
            { assertTrue(physics.ball.x == pika.core.GROUND_WIDTH - 56, "ball.x 가 서브권을 반영하지 않았다") },
            { assertTrue(physics.ball.yVelocity == 1, "ball.yVelocity 가 리셋되지 않았다") },
        )
    }

    @Test
    @DisplayName("서브권 판정은 ball.punchEffectX 를 읽는다 (업스트림과 동일 필드)")
    fun serveSideUsesPunchEffectX() {
        val rng = XorShift32(1)
        val physics = pika.core.PikaPhysics(false, false, pika.core.Rand { rng.nextRand() })

        physics.ball.x = 400            // ball.x 는 오른쪽
        physics.ball.punchEffectX = 10  // 그러나 실제로 읽는 값은 왼쪽
        assertTrue(Harness.nextServeIsPlayer2(physics.ball), "punchEffectX 가 아니라 x 를 읽고 있다")

        physics.ball.punchEffectX = pika.core.GROUND_HALF_WIDTH
        assertTrue(!Harness.nextServeIsPlayer2(physics.ball), "경계값(=216)은 player1 서브여야 한다")
    }
}
