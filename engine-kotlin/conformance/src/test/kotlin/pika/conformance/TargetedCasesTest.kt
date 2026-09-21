package pika.conformance

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pika.core.GROUND_HALF_WIDTH
import pika.core.NET_PILLAR_HALF_WIDTH
import pika.core.NET_PILLAR_TOP_TOP_Y_COORD
import pika.core.PikaPhysics
import kotlin.math.abs

/**
 * 표적 케이스가 **실제로 무언가를 겨냥하고 있는지** 확인한다. (tasks.md P7)
 *
 * 커버리지 100% 는 `game_ended` 분기를 밟았다는 것만 증명한다.
 * 나머지 케이스가 의도한 희귀 상태를 만들고 있는지는 보장하지 않는다 —
 * `hyper-ball-stuck` 의 `fine_rotation` 을 49 로 바꿔도 커버리지는 100% 를 유지한다.
 *
 * 그래서 케이스마다 "이 케이스는 이것을 겨냥한다" 를 실행 가능한 단언으로 박아둔다.
 * 이 테스트는 Node 없이 돌므로 `gradlew build` 에 포함된다.
 */
class TargetedCasesTest {

    /** 케이스 하나를 돌리며 프레임마다 술어를 평가해, 한 번이라도 참이 되었는지 본다. */
    private fun anyFrame(caseName: String, predicate: (PikaPhysics, Boolean) -> Boolean): Boolean {
        val index = TargetedCases.all.indexOfFirst { it.name == caseName }
        require(index >= 0) { "그런 표적 케이스가 없습니다: $caseName" }
        var hit = false
        Harness.runTargetedCase(index + 1) { physics, touching, _, _ ->
            if (!hit && predicate(physics, touching)) hit = true
        }
        return hit
    }

    private fun assertTargets(caseName: String, what: String, predicate: (PikaPhysics, Boolean) -> Boolean) =
        assertTrue(anyFrame(caseName, predicate)) { "$caseName 이 더 이상 $what 을 겨냥하지 않습니다" }

    @Test
    @DisplayName("경기 종료 케이스가 승리(5)·패배(6) 모션에 실제로 진입한다")
    fun gameEndReachesWinLoseStates() {
        assertAll(
            { assertTargets("game-end-p1-wins", "승리 모션(state 5)") { p, _ -> p.player1.state == 5 } },
            { assertTargets("game-end-p1-wins", "패배 모션(state 6)") { p, _ -> p.player2.state == 6 } },
            { assertTargets("game-end-p2-wins", "승리 모션(state 5)") { p, _ -> p.player2.state == 5 } },
            { assertTargets("game-end-p2-wins", "패배 모션(state 6)") { p, _ -> p.player1.state == 6 } },
            // processGameEndFrameFor 가 프레임을 실제로 진행시켰는가 (frameNumber 0 → 4)
            { assertTargets("game-end-p1-wins", "종료 모션 프레임 진행") { p, _ -> p.player1.state == 5 && p.player1.frameNumber == 4 } },
        )
    }

    @Test
    @DisplayName("경기 종료가 공중에서 선언되면 모션 진입이 미뤄진다")
    fun gameEndWhileAirborneDefersMotion() {
        // state != 0 인 동안에는 gameEnded 여도 5/6 으로 넘어가지 않는다.
        assertTargets("game-end-while-airborne", "점프 중 종료 상태") { p, _ ->
            p.player1.gameEnded && p.player1.state == 1
        }
        assertTargets("game-end-while-airborne", "그 뒤의 승리 모션 진입") { p, _ -> p.player1.state == 5 }
    }

    @Test
    @DisplayName("hyper ball 글리치가 재현된다 (rotation 이 5 에 고착)")
    fun hyperBallGlitchReproduced() {
        // 단발이 아니라 **고착**이어야 한다. 연속 프레임 수를 센다.
        // 업스트림 주석: "충돌이 일어날 때까지" 고착된다. 무작위 입력에서는 그게 수십 프레임이다.
        for (name in listOf("hyper-ball-stuck", "hyper-ball-fsm")) {
            var streak = 0
            var best = 0
            val index = TargetedCases.all.indexOfFirst { it.name == name } + 1
            Harness.runTargetedCase(index) { physics, _, _, _ ->
                if (physics.ball.rotation == 5) { streak++; if (streak > best) best = streak } else streak = 0
            }
            assertTrue(best >= 10) { "$name: rotation 5 고착이 $best 프레임밖에 지속되지 않았습니다 (글리치 미재현)" }
        }
    }

    @Test
    @DisplayName("fine_rotation 보정 분기 양쪽(음수 → +50, 50 초과 → -50)을 밟는다")
    fun fineRotationWrapsBothWays() {
        assertAll(
            { assertTargets("fine-rotation-wrap-negative", "음수 보정") { p, _ -> p.ball.fineRotation > 30 } },
            { assertTargets("fine-rotation-wrap-positive", "초과 보정") { p, _ -> p.ball.fineRotation < 20 } },
        )
    }

    @Test
    @DisplayName("네트 기둥 윗면·양 옆면 충돌을 모두 밟는다")
    fun netPillarCollisions() {
        val atNet = { p: PikaPhysics -> abs(p.ball.x - GROUND_HALF_WIDTH) < NET_PILLAR_HALF_WIDTH }
        assertAll(
            { assertTargets("net-pillar-top", "기둥 윗면") { p, _ -> atNet(p) && p.ball.y > NET_PILLAR_TOP_TOP_Y_COORD && p.ball.y <= 192 } },
            { assertTargets("net-pillar-left-side", "기둥 왼쪽 옆면") { p, _ -> atNet(p) && p.ball.y > 192 && p.ball.x < GROUND_HALF_WIDTH } },
            { assertTargets("net-pillar-right-side", "기둥 오른쪽 옆면") { p, _ -> atNet(p) && p.ball.y > 192 && p.ball.x >= GROUND_HALF_WIDTH } },
        )
    }

    @Test
    @DisplayName("누운 상태(state 4)에서 공에 맞는 경로를 밟는다")
    fun lyingDownCollision() {
        assertAll(
            { assertTargets("lying-down-collision", "경직 중 충돌") { p, _ -> p.player1.state == 4 && p.player1.isCollisionWithBallHappened } },
            { assertTargets("lying-down-collision-p2", "경직 중 충돌") { p, _ -> p.player2.state == 4 && p.player2.isCollisionWithBallHappened } },
        )
    }

    @Test
    @DisplayName("좌우 경계 반사를 양쪽 다 밟는다 (경계 조건이 비대칭이다)")
    fun ballBoundaryBounces() {
        assertAll(
            { assertTargets("ball-left-boundary", "왼쪽 경계 반사") { p, _ -> p.ball.x < 30 && p.ball.xVelocity > 0 } },
            { assertTargets("ball-right-boundary", "오른쪽 경계 반사") { p, _ -> p.ball.x > 420 && p.ball.xVelocity < 0 } },
        )
    }

    @Test
    @DisplayName("케이스 표가 State Spec 과 일치한다 (오타가 조용히 무시되지 않는다)")
    fun caseTableMatchesSpec() {
        assumeTrue(RepoPaths.targetedCases.toFile().exists())
        assertEquals(StateSpec.BASE_INT_COUNT - 1, TargetedCases.assertFieldsMatchSpec())

        val rng = XorShift32(1)
        val scratch = PikaPhysics(false, false, pika.core.Rand { rng.nextRand() })
        assertThrows<IllegalStateException> { TargetedCases.applySetting(scratch, "ball.no_such_field", 1) }
        assertThrows<IllegalStateException> { TargetedCases.applySetting(scratch, "player3.x", 1) }
    }

    @Test
    @DisplayName("표적 케이스는 표적 케이스를 기본 생성기로 삼을 수 없다")
    fun targetedCannotNest() {
        val thrown = assertThrows<IllegalArgumentException> {
            TargetedCases.parse("case bad targeted 1 10\n")
        }
        assertTrue(thrown.message!!.contains("targeted"), "메시지: ${thrown.message}")
    }
}
