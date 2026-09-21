package pika.conformance

/**
 * xorshift32 — `tools/js-oracle/xorshift32.mjs` 와 **비트 단위로 동일한** 수열을 낸다.
 *
 * 시프트/XOR 만 쓰므로 JS 의 uint32 연산과 Kotlin 의 signed Int 연산이 같은 비트를 만든다.
 * (`ushr` 은 부호 없는 오른쪽 시프트라 JS `>>>` 와 같다.)
 */
class XorShift32(seed: Int) {
    private var x: Int = if (seed == 0) ZERO_SEED_REPLACEMENT else seed

    /** 다음 값. 비트 패턴은 JS 의 uint32 와 같다 (부호만 다르게 해석될 뿐). */
    fun nextUInt(): Int {
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        return x
    }

    /**
     * 업스트림 `rand()` 에 해당하는 [0, 32767] 값.
     *
     * JS 쪽은 `Math.floor(32768 * (u / 2^32))` 를 계산하는데, 2의 거듭제곱 스케일링이라
     * double 오차가 없고 결과는 항상 `u >>> 17` 과 같다. (plan.md §4)
     */
    fun nextRand(): Int = nextUInt() ushr 17

    companion object {
        /** xorshift 는 상태 0 에서 영원히 0 이다. 시드가 0 이면 이 값으로 대체한다. */
        const val ZERO_SEED_REPLACEMENT: Int = -1640531527 // 0x9E3779B9
    }
}
