package pika.env

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import pika.core.PikaUserInput

/** 행동 공간과 엣지 변환의 계약. (tasks.md P3, FR-4, FR-5) */
class ActionCodecTest {

    @Test
    @DisplayName("Discrete(18) 은 (x, y, powerHit) 과 일대일 대응이다")
    fun bijection() {
        val seen = mutableSetOf<Triple<Int, Int, Boolean>>()
        for (a in 0 until ActionCodec.ACTION_COUNT) {
            val t = Triple(ActionCodec.xDirection(a), ActionCodec.yDirection(a), ActionCodec.powerHitHeld(a))
            assertTrue(t.first in -1..1 && t.second in -1..1, "행동 $a 의 방향이 범위를 벗어납니다: $t")
            assertTrue(seen.add(t), "행동 $a 가 이미 나온 조합입니다: $t")
            assertEquals(a, ActionCodec.encode(t.first, t.second, t.third), "encode ∘ decode 가 항등이 아닙니다")
        }
        assertEquals(18, seen.size)
    }

    @Test
    @DisplayName("엣지 변환: 같은 행동을 계속 눌러도 powerHit 은 첫 프레임에만 1 이다")
    fun powerHitFiresOnlyOnEdge() {
        val edge = EdgeTrigger()
        val out = PikaUserInput()
        val hold = ActionCodec.encode(1, 0, powerHitHeld = true)

        ActionCodec.decode(hold, out, edge)
        assertEquals(1, out.powerHit, "누르는 순간")
        repeat(5) {
            ActionCodec.decode(hold, out, edge)
            assertEquals(0, out.powerHit, "누르고 있는 동안은 0 — keyboard.js:71-77 과 같은 규칙")
        }

        // 뗐다가 다시 누르면 또 1.
        ActionCodec.decode(ActionCodec.encode(1, 0, powerHitHeld = false), out, edge)
        assertEquals(0, out.powerHit)
        ActionCodec.decode(hold, out, edge)
        assertEquals(1, out.powerHit)
    }

    @Test
    @DisplayName("엣지 변환을 끄면 누르고 있는 동안 계속 1 이다 (Phase 3 의 A/B 용)")
    fun edgeTriggerCanBeDisabled() {
        val out = PikaUserInput()
        val hold = ActionCodec.encode(0, -1, powerHitHeld = true)
        repeat(3) {
            ActionCodec.decode(hold, out, edge = null)
            assertEquals(1, out.powerHit)
        }
    }

    @Test
    @DisplayName("방향은 엣지 변환의 대상이 아니다 — 계속 눌러야 계속 움직인다")
    fun directionsAreLevelNotEdge() {
        val edge = EdgeTrigger()
        val out = PikaUserInput()
        repeat(3) {
            ActionCodec.decode(ActionCodec.encode(-1, 1, powerHitHeld = true), out, edge)
            assertEquals(-1, out.xDirection)
            assertEquals(1, out.yDirection)
        }
    }

    @Test
    @DisplayName("EdgeTrigger.reset 은 눌림 상태를 지운다")
    fun resetClearsHeldState() {
        val edge = EdgeTrigger()
        assertEquals(1, edge.apply(true))
        assertEquals(0, edge.apply(true))
        edge.reset()
        assertFalse(edge.wasHeld)
        assertEquals(1, edge.apply(true), "리셋 후에는 다시 엣지로 취급한다")
    }
}
