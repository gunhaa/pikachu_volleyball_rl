package pika.conformance

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * State Spec 의 계약을 본다. (tasks.md P3)
 *
 * 필드 순서의 단일 진실 공급원은 `proto/state_spec.proto` 다.
 * Kotlin 과 JS 가 각자 목록을 들고 있되, 양쪽 다 .proto 와 대조한다.
 */
class StateSpecTest {

    @Test
    @DisplayName("StateSpec.kt 의 필드 순서가 proto/state_spec.proto 와 같다")
    fun matchesProto() {
        StateSpec.assertMatchesProto()
    }

    @Test
    @DisplayName("필드 수: 기본 44, 엄격 모드 52")
    fun fieldCounts() {
        assertEquals(44, StateSpec.intCount(strict = false))
        assertEquals(52, StateSpec.intCount(strict = true))
        assertEquals(44, StateSpec.fieldNames(strict = false).size)
        assertEquals(52, StateSpec.fieldNames(strict = true).size)
    }

    @Test
    @DisplayName("is_ball_touching_ground 가 기본 필드의 마지막이다")
    fun touchingGroundIsLastBaseField() {
        assertEquals("is_ball_touching_ground", StateSpec.baseFieldNames.last())
    }

    @Test
    @DisplayName("Int 는 little-endian 4바이트로 직렬화된다 (음수 포함)")
    fun packsLittleEndian() {
        val ints = intArrayOf(1, -1, 0x01020304, Int.MIN_VALUE)
        val out = ByteArray(ints.size * 4)
        StateSpec.packInts(ints, out)
        assertArrayEquals(
            byteArrayOf(
                1, 0, 0, 0,
                -1, -1, -1, -1,
                0x04, 0x03, 0x02, 0x01,
                0, 0, 0, 0x80.toByte(),
            ),
            out,
        )
    }

    @Test
    @DisplayName("프레임별 해시는 SHA-256 앞 8바이트를 hex 16자로 자른다")
    fun frameHashIsTruncatedTo16Hex() {
        val digest = StateSpec.sha256()
        val h = StateSpec.frameHash(digest, ByteArray(44 * 4))
        assertEquals(16, h.length)
        // 같은 입력 → 같은 해시 (digest 재사용 시 reset 누락 방지)
        assertEquals(h, StateSpec.frameHash(digest, ByteArray(44 * 4)))
    }

    @Test
    @DisplayName("체인 해시는 절단하지 않은 32바이트다")
    fun chainHashIs32Bytes() {
        val digest = StateSpec.sha256()
        val h = StateSpec.chainStep(digest, StateSpec.chainSeed(), ByteArray(44 * 4))
        assertEquals(32, h.size)
    }
}
