package pika.env

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 관측·보상 골든 회귀. (tasks.md P4, M2-d)
 *
 * ⚠️ 여기가 빨간불이면 `writeEnvGolden` 으로 덮어쓰기 **전에** 무엇이 바뀌었는지 찾는다.
 *    레이아웃 해시가 함께 바뀌었으면 범인은 관측 레이아웃이고, 레이아웃 해시는 그대로인데
 *    체인만 바뀌었으면 범인은 값(정규화·보상·리셋 순서·시드 유도) 이다.
 *    골든 파일이 그 두 가지를 나눠 보여주는 이유가 이것이다.
 */
class EnvGoldenRegressionTest {

    @Test
    @DisplayName("커밋된 체인 해시와 현재 env 의 결과가 일치한다")
    fun goldenMatches() {
        assertTrue(EnvGolden.exists()) {
            "골든 파일이 없습니다: ${EnvPaths.goldenEnvChain}\n" +
                "./gradlew :engine-kotlin:env:writeEnvGolden 으로 생성하세요."
        }
        val entries = EnvGolden.load()
        assertEquals(EnvGolden.CASES.size, entries.size, "골든 케이스 수가 CASES 와 다릅니다. 의도한 변경입니까?")

        val failures = EnvGolden.verify(entries)
        assertTrue(failures.isEmpty()) {
            buildString {
                appendLine("골든과 어긋난 케이스 ${failures.size}건 / ${entries.size}건")
                for (f in failures) {
                    appendLine("  ${f.entry.name} (N=${f.entry.numEnvs}, ${f.entry.frames}프레임)")
                    if (f.entry.layoutHash16 != f.actual.layoutHash16) {
                        appendLine("    레이아웃: ${f.entry.layoutHash16} → ${f.actual.layoutHash16}  ← 관측 레이아웃이 바뀌었습니다")
                    }
                    appendLine("    체인 골든: ${f.entry.chainHex}")
                    appendLine("    체인 현재: ${f.actual.chainHex}")
                }
                appendLine()
                appendLine("의도한 변경이면 ./gradlew :engine-kotlin:env:writeEnvGolden 으로 갱신하고")
                appendLine("**무엇을 왜 바꿨는지 커밋 메시지에 적으세요** (plan.md §10).")
            }
        }
    }

    @Test
    @DisplayName("골든 케이스가 플래그를 모두 덮는다 — 안 밟는 코드 경로는 회귀를 못 잡는다")
    fun casesCoverFlags() {
        val cases = EnvGolden.CASES
        assertTrue(cases.any { it.config.slots == Slots.EXTERNAL_VS_FSM }, "Track A")
        assertTrue(cases.any { it.config.slots == Slots.FSM_VS_EXTERNAL }, "Track A 진영 반전")
        assertTrue(cases.any { it.config.slots == Slots.EXTERNAL_VS_EXTERNAL }, "Track B")
        // ⚠️ "플래그마다 줄이 있다" 와 "그 코드 경로를 밟는다" 는 다른 말이다.
        //    미러링은 오른쪽 슬롯에만 걸리므로 오른쪽이 외부인 케이스여야 의미가 있다.
        assertTrue(
            cases.any { !it.config.mirrorObservations && !it.config.slots.p2.isFsm },
            "미러링 off 케이스가 오른쪽 외부 슬롯을 갖고 있지 않습니다 — 아무것도 시험하지 못합니다",
        )
        assertTrue(cases.any { !it.config.obs.includeExpectedLanding }, "착지점 off")
        assertTrue(cases.any { it.config.obs.includeSideFlag }, "진영 플래그 on")
        assertTrue(cases.any { !it.config.edgeTriggerPowerHit }, "엣지 변환 off")
        assertTrue(cases.any { it.config.rewardWeights != RewardWeights() }, "셰이핑 가중치")
        assertTrue(cases.any { it.config.maxRallyFrames in 1..500 }, "truncation 경로")
    }

    @Test
    @DisplayName("truncating 케이스는 실제로 truncation 을 밟는다")
    fun truncatingCaseActuallyTruncates() {
        val case = EnvGolden.CASES.first { it.name == "truncating" }
        val vec = VectorEnv(case.config, case.numEnvs)
        vec.reset()
        val actions = EnvGolden.ActionSequence(case.actionSeed, case.numEnvs, vec.slotCount)
        var truncations = 0
        repeat(case.frames) {
            vec.step(actions.next())
            truncations += vec.truncated.count { it.toInt() == 1 }
        }
        assertTrue(truncations > 0, "truncation 을 한 번도 안 밟았습니다 — 케이스가 무의미합니다")
    }
}
