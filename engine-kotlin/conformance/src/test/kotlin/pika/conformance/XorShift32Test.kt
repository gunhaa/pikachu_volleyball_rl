package pika.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import pika.core.XorShift32

/**
 * Kotlin xorshift32 가 JS 구현과 **비트 단위로 일치**하는지 본다. (tasks.md P5)
 *
 * 참조 벡터는 `tools/js-oracle/selfcheck.mjs` 가 찍은 값을 그대로 옮긴 것이다.
 * 이 두 수열이 갈라지면 물리가 아무리 맞아도 FSM 의 행동과 computerBoldness 가 달라진다.
 */
class XorShift32Test {

    /** JS: `xorshift32(1)` 의 처음 8개 uint32 */
    private val jsUInts = longArrayOf(
        270369, 67634689, 2647435461, 307599695,
        2398689233, 745495504, 632435482, 435756210,
    )

    /** JS: 같은 수열의 `u >>> 17` — 업스트림 `rand()` 에 해당하는 [0, 32767] 값 */
    private val jsRands = intArrayOf(2, 516, 20198, 2346, 18300, 5687, 4825, 3324)

    @Test
    @DisplayName("uint32 수열이 JS 와 비트 단위로 같다")
    fun uintSequenceMatchesJs() {
        val rng = XorShift32(1)
        for (i in jsUInts.indices) {
            val actual = rng.nextUInt().toUInt().toLong()
            assertEquals(jsUInts[i], actual, "index=$i")
        }
    }

    @Test
    @DisplayName("rand() 상당값이 JS 와 같다 — 부동소수 경로에 오차가 없다")
    fun randSequenceMatchesJs() {
        val rng = XorShift32(1)
        for (i in jsRands.indices) {
            val v = rng.nextRand()
            assertEquals(jsRands[i], v, "index=$i")
            assert(v in 0..32767) { "rand() 는 [0, 32767] 이어야 한다: $v" }
        }
    }

    @Test
    @DisplayName("시드 0 에서 고착되지 않는다")
    fun zeroSeedDoesNotGetStuck() {
        val rng = XorShift32(0)
        val a = rng.nextUInt()
        val b = rng.nextUInt()
        assertNotEquals(0, a)
        assertNotEquals(a, b)
    }

    @Test
    @DisplayName("umod 는 난수를 uint32 로 다룬다 — Int % 는 음수를 낸다")
    fun umodTreatsValuesAsUnsigned() {
        val negative = -1 // uint32 로는 4294967295
        assertEquals(-1 % 100, -1, "전제 확인: Kotlin Int % 는 음수를 낸다")
        assertEquals(95, umod(negative, 100))
        assertEquals(0, umod(negative, 3)) // 4294967295 % 3 == 0
    }
}
