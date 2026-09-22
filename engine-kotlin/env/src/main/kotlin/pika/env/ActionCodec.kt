package pika.env

import pika.core.PikaUserInput

/**
 * 행동 공간 `Discrete(18)` = (xDirection 3) × (yDirection 3) × (powerHit 2). (FR-4)
 *
 * `MultiDiscrete([3,3,2])` 대신 단일 categorical 을 쓴다 — PPO 의 log-prob·엔트로피·KL
 * 계산이 단순하고, 무엇보다 **축이 독립이라는 가정이 틀리기 때문**이다. FSM 1.2M 프레임
 * 측정에서 `yDirection = 1`(아래) 은 `powerHit = 1` 과 함께가 아니면 나오지 않았다.
 * y 방향은 사실상 파워히트의 방향 지정용이다 (plan.md §2.4).
 *
 * 덤으로 알아낸 것: FSM 이 실제로 쓰는 행동은 18개 중 13개다. Phase 3 이 정책의
 * 행동 분포를 볼 때의 기준선이 된다.
 */
object ActionCodec {

    const val ACTION_COUNT = 18

    /** -1, 0, 1 */
    fun xDirection(action: Int): Int = action / 6 - 1

    /** -1(위), 0, 1(아래) */
    fun yDirection(action: Int): Int = (action % 6) / 2 - 1

    /** 키를 **누르고 있는가**. 엣지 변환 전의 값이다. */
    fun powerHitHeld(action: Int): Boolean = action % 2 == 1

    /** 역방향. 테스트와 진단용. */
    fun encode(xDirection: Int, yDirection: Int, powerHitHeld: Boolean): Int =
        (xDirection + 1) * 6 + (yDirection + 1) * 2 + if (powerHitHeld) 1 else 0

    /**
     * 행동을 엔진 입력으로 푼다.
     *
     * @param edge 엣지 변환기. null 이면 누른 상태가 그대로 `powerHit` 이 된다
     *   (`edgeTriggerPowerHit = false`, Phase 3 의 A/B 용).
     */
    fun decode(action: Int, out: PikaUserInput, edge: EdgeTrigger?) {
        require(action in 0 until ACTION_COUNT) { "행동은 0..17 이어야 합니다: $action" }
        out.xDirection = xDirection(action)
        out.yDirection = yDirection(action)
        val held = powerHitHeld(action)
        out.powerHit = if (edge != null) edge.apply(held) else if (held) 1 else 0
    }
}

/**
 * `keyboard.js:71-77` 과 같은 규칙 — 키가 **눌리는 순간**에만 1. (FR-5)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ⚠️ 이것은 **에이전트 슬롯에만** 걸린다. 규율이 아니라 구조다.
 * ─────────────────────────────────────────────────────────────────────────────
 * FSM 은 엔진 **안에서** `userInput.powerHit` 에 직접 쓰므로 (`PhysicsEngine.kt:129`)
 * 바깥의 이 변환기가 닿을 곳이 없다. FSM 입력의 절반 가까이가 "직전 프레임도 1" 이므로
 * (p1 58.6%, p2 42.8% — plan.md §2.4) 여기에 엣지 변환을 걸면 Phase 1 이 증명한
 * 동치성이 깨진다.
 *
 * ⚠️ 랠리가 바뀌면 [reset] 한다. 안 하면 이전 랠리 마지막 프레임의 키 상태가 새 랠리로
 *    새어 들어가 첫 프레임의 파워히트가 먹히지 않는다. `keyboard.js` 에는 이 리셋이 없지만
 *    (사람은 랠리 사이에 손을 뗀다) 우리 에이전트는 안 뗀다.
 */
class EdgeTrigger {
    var wasHeld: Boolean = false
        private set

    fun apply(held: Boolean): Int {
        val edge = !wasHeld && held
        wasHeld = held
        return if (edge) 1 else 0
    }

    fun reset() {
        wasHeld = false
    }
}
