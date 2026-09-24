package pika.env

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import pika.core.GROUND_WIDTH
import pika.env.replay.ReplayCodec
import java.nio.file.Files

/**
 * 결정 관측 골든 회귀. (Phase 5 P1, plan.md §3.4)
 *
 * ⚠️ 빨간불이면 `writeObsGolden` 으로 덮어쓰기 **전에** 무엇이 바뀌었는지 찾는다.
 *    `EnvGoldenRegressionTest` 가 초록인데 여기만 빨갛다면 범인은 생성기(결정 관측의 정의 · 리플레이 컷)다.
 */
class ObsGoldenTest {

    private val results by lazy { ObsGolden.computeAll() }

    @Test
    @DisplayName("체인 파일 = 다시 계산한 결과 (텍스트 바이트 비교)")
    fun chainsMatch() {
        val file = EnvPaths.goldenObsDir.resolve(ObsGolden.CHAINS_FILE)
        assertTrue(Files.exists(file)) { "골든이 없습니다: $file — ./gradlew :engine-kotlin:env:writeObsGolden" }
        assertEquals(file.toFile().readText(), ObsGolden.render(results)) {
            "결정 관측 체인이 바뀌었습니다. 의도한 변경이면 writeObsGolden 으로 갱신하고 이유를 커밋 메시지에 적으세요."
        }
    }

    @Test
    @DisplayName("JS 대조용 상수 파일(레이아웃 해시 · deriveSeed 표) = 현재 Kotlin 값")
    fun constantsMatch() {
        val file = EnvPaths.goldenObsDir.resolve(ObsGolden.CONSTANTS_FILE)
        assertTrue(Files.exists(file)) { "상수 파일이 없습니다: $file — ./gradlew :engine-kotlin:env:writeObsGolden" }
        assertEquals(file.toFile().readText(), ObsGolden.renderConstants())
    }

    @Test
    @DisplayName("리플레이 바이트 = 다시 만든 리플레이, 남는 파일 없음")
    fun replaysMatch() {
        val dir = EnvPaths.goldenObsDir
        val expected = results.flatMap { r -> r.envs.flatMap { it.games } }
        for ((name, replay) in expected) {
            val path = dir.resolve(name)
            assertTrue(Files.exists(path)) { "리플레이가 없습니다: $name" }
            assertArrayEquals(Files.readAllBytes(path), ReplayCodec.encode(replay), "리플레이 바이트가 다릅니다: $name")
        }
        val onDisk = Files.list(dir).use { s -> s.map { it.fileName.toString() }.filter { it.endsWith(".pkr") }.toList() }
        assertEquals(expected.map { it.first }.sorted(), onDisk.sorted(), "골든 디렉터리의 리플레이 목록")
    }

    @Test
    @DisplayName("케이스 목록이 EnvGolden.CASES 이름을 전부 포함한다 (+ 진영 플래그 두 케이스)")
    fun coversEnvGoldenCases() {
        val names = ObsGolden.CASES.map { it.name }
        for (c in EnvGolden.CASES) assertTrue(c.name in names) { "EnvGolden 케이스 '${c.name}' 가 빠졌습니다" }
        assertTrue("side-flag-right" in names && "side-flag-both" in names)
        assertEquals(names.size, names.toSet().size, "케이스 이름 중복")
    }

    @Test
    @DisplayName("결정 수 = 물리 프레임 수 — autoreset 스텝은 세지 않는다")
    fun decisionsEqualPhysicsFrames() {
        var sawAutoreset = false
        for (r in results) for (e in r.envs) {
            val frames = e.games.sumOf { it.second.frameCount }
            assertEquals(frames, e.decisions, "${r.case.name} e${e.envIndex}: 결정 수 ≠ 리플레이 프레임 합")
            if (e.decisions < r.case.frames) sawAutoreset = true
            // 마지막 게임만 미완일 수 있다 — 케이스 끝의 컷.
            e.games.dropLast(1).forEach { assertTrue(it.second.ended) { "${it.first}: 중간 게임이 미완" } }
        }
        assertTrue(sawAutoreset, "autoreset 이 한 번도 없었다 — 이 테스트가 아무것도 시험하지 못한다")
    }

    @Test
    @DisplayName("side-flag-right 는 미러 + 진영 플래그 +1 을 실제로 밟는다")
    fun sideFlagRightIsMirroredWithFlag() {
        val case = ObsGolden.CASES.first { it.name == "side-flag-right" }
        val dim = case.config.obsDim

        var first: FloatArray? = null
        var firstSlot = -1
        ObsGolden.run(case.copy(numEnvs = 1, frames = 1)) { _, decision, slot, obs, offset, d ->
            if (decision == 0) {
                first = obs.copyOfRange(offset, offset + d)
                firstSlot = slot
            }
        }
        val obs = requireNotNull(first) { "결정 관측이 없습니다" }
        assertEquals(1, firstSlot, "외부 슬롯은 오른쪽이어야 한다")

        // 같은 구성의 리셋 상태에서 필드를 직접 계산해 대조한다.
        val env = PikaEnv(case.config, 0)
        env.reset(FloatArray(dim), 0)
        val p = env.game.physics
        // 미러 후 "나"(player2)는 왼쪽 진영 범위로 정규화된다.
        assertEquals(ObsEncoder.unit(GROUND_WIDTH - p.player2.x, ObsEncoder.LEFT_X_MIN, ObsEncoder.LEFT_X_MAX), obs[0])
        assertNotEquals(ObsEncoder.unit(p.player2.x, ObsEncoder.RIGHT_X_MIN, ObsEncoder.RIGHT_X_MAX), obs[0])
        // 공 x — 선수 블록 두 개(각 15칸) 뒤.
        assertEquals(ObsEncoder.unit(GROUND_WIDTH - p.ball.x, ObsEncoder.BALL_X_MIN, ObsEncoder.BALL_X_MAX), obs[30])
        // 진영 플래그는 미러링하지 않는다 — 오른쪽 = +1.
        assertEquals(1f, obs[dim - 1], "진영 플래그")
    }
}
