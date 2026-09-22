package pika.env

import pika.core.PikaUserInput

/**
 * 한 진영을 누가 조종하는가. (FR-8)
 *
 * 이것은 편의를 위한 구분이 아니라 **M2-g 를 코드가 강제하게 만드는 장치**다 (plan.md §9).
 * [External] 슬롯은 `Player.isComputer = false` 로 생성되므로
 * `processPlayerMovementAndSetPlayerPosition` 의 `if (player.isComputer)` 분기에
 * **들어갈 수가 없다.** "주의해서 FSM 을 안 쓴다" 가 아니라 구조가 막는다.
 */
sealed interface Slot {
    /** 엔진 내장 FSM(`letComputerDecideUserInput`). 행동을 받지 않고 관측도 내보내지 않는다. */
    data object Fsm : Slot

    /** 외부 정책. 행동을 받고 관측을 내보낸다. */
    data object External : Slot

    val isFsm: Boolean get() = this === Fsm
}

/** 두 진영의 구성. Track A = `(External, Fsm)`, Track B = `(External, External)`. */
data class Slots(val p1: Slot, val p2: Slot) {
    /** 이 구성이 FSM 코드 경로를 한 번이라도 탈 수 있는가. M2-g 불변식의 기준값이다. */
    val usesFsm: Boolean get() = p1.isFsm || p2.isFsm

    operator fun get(index: Int): Slot = if (index == 0) p1 else p2

    companion object {
        val FSM_VS_FSM = Slots(Slot.Fsm, Slot.Fsm)
        val EXTERNAL_VS_FSM = Slots(Slot.External, Slot.Fsm)
        val FSM_VS_EXTERNAL = Slots(Slot.Fsm, Slot.External)
        val EXTERNAL_VS_EXTERNAL = Slots(Slot.External, Slot.External)
    }
}

/**
 * [Slot.External] 슬롯의 입력원.
 *
 * ⚠️ [Slot.Fsm] 슬롯에는 쓰이지 않는다 — FSM 은 엔진 **안에서** `userInput` 을 직접
 *    덮어쓰므로 (`PhysicsEngine.kt:129`) 바깥에서 채워 넣어도 버려진다.
 *    FR-5 의 "엣지 변환을 FSM 에 적용하지 않는다" 가 규율이 아니라 구조인 이유다.
 */
fun interface Controller {
    /**
     * 이번 프레임의 입력을 [out] 에 채운다.
     *
     * @param isPlayer2 이 슬롯이 오른쪽 진영인가
     */
    fun decide(game: PikaGame, isPlayer2: Boolean, out: PikaUserInput)
}
