package pika.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * CI 골든 회귀. (tasks.md P8 / NFR-2)
 *
 * **Node 도 `upstream/` 도 필요 없다.** 커밋된 체인 해시와 Kotlin 엔진의 결과만 대조한다.
 * 전수 차분(`./gradlew conformance`) 은 로컬에서 돌리고, CI 는 이 테스트로 회귀만 잡는다.
 *
 * ⚠️ 여기가 빨간불이면 `--write-golden` 으로 덮어쓰지 말 것. 먼저 전수 차분으로 원인을 찾는다.
 *    골든을 다시 만들어 초록으로 되돌리는 것은 검증을 통과시키는 게 아니라 무력화하는 것이다.
 */
class GoldenRegressionTest {

    @Test
    @DisplayName("커밋된 체인 해시와 Kotlin 엔진의 결과가 일치한다 (Node 불필요)")
    fun goldenChainHashesMatch() {
        assertTrue(Golden.exists()) {
            "골든 파일이 없습니다: ${RepoPaths.goldenChainHashes}\n" +
                "./gradlew conformance --args=\"--write-golden\" 으로 생성하세요."
        }

        val entries = Golden.load()
        assertEquals(615, entries.size, "골든 표본 크기가 바뀌었습니다. 의도한 변경입니까?")

        val failures = Golden.verify(entries)
        assertTrue(failures.isEmpty()) {
            buildString {
                appendLine("골든과 어긋난 에피소드 ${failures.size}건 / ${entries.size}건")
                for (f in failures.take(10)) {
                    appendLine("  ${f.entry.gen.cliName} seed=${f.entry.seed} T=${f.entry.frames}")
                    appendLine("    골든: ${f.entry.chainHex}")
                    appendLine("    현재: ${f.actualHex}")
                }
                appendLine()
                appendLine("원인을 찾으려면: ./gradlew conformance --args=\"--gen <생성기> --seeds <시드>\"")
                appendLine("불일치 위치는 전수 차분이 프레임 단위로 짚어 줍니다. 골든을 덮어쓰지 마세요.")
            }
        }
    }

    @Test
    @DisplayName("골든 표본이 네 생성기를 모두 덮는다")
    fun goldenCoversAllGenerators() {
        val gens = Golden.load().map { it.gen }.toSet()
        assertEquals(Generator.entries.toSet(), gens, "골든이 빠뜨린 생성기가 있습니다")
    }
}
