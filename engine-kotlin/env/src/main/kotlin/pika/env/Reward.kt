package pika.env

/**
 * 보상 항의 가중치. (FR-6)
 *
 * **Phase 2 의 기본값은 `rallyWin = 1`, 나머지 전부 0 이다.** 튜닝은 Phase 3 의 일이고,
 * 여기서는 항을 계산해 **내보내기만** 한다 (PRD §6).
 *
 * ⚠️ 항의 부호는 항 안에 들어 있다 (`rallyWin` 은 ±1, `timePenalty` 는 프레임당 -1).
 *    가중치는 **크기**다. 음수 가중치를 넣으면 페널티가 보상이 된다.
 *
 * 셰이핑이 왜 미리 만들어져 있어야 하는가: 랜덤 정책끼리의 랠리는 평균 44.8 프레임이다
 * (plan.md §2.3). 즉 랜덤끼리는 공을 거의 못 친다. `rallyWin` 만 주면 신호가 랠리당 1개,
 * 그것도 거의 무작위다. Track B 는 그 상태로는 학습이 시작되지 않는다.
 */
data class RewardWeights(
    val rallyWin: Float = 1f,
    val ballTouch: Float = 0f,
    val crossedNet: Float = 0f,
    val opponentMiss: Float = 0f,
    val timePenalty: Float = 0f,
) {
    fun toFloatArray(): FloatArray = floatArrayOf(rallyWin, ballTouch, crossedNet, opponentMiss, timePenalty)
}

/**
 * 보상 항의 인덱스와 이름. 항별 값은 합계와 **함께** 내보낸다 (FR-6).
 *
 * 합계만 주면 Phase 3 의 어닐링이 "지금 보상의 몇 %가 셰이핑에서 왔는가" 에 답할 수 없다.
 * 그 질문에 답하지 못하면 어닐링 시점을 짐작으로 정하게 된다.
 */
object RewardTerms {
    /** 랠리 승 +1 / 패 -1 (종단 보상). truncation 에서는 0 이다 — 승자가 없다. */
    const val RALLY_WIN = 0

    /** 이번 프레임에 내가 공을 쳤다 (셰이핑). */
    const val BALL_TOUCH = 1

    /** 내가 마지막으로 친 공이 이번 프레임에 네트를 넘어 상대 진영으로 갔다 (셰이핑). */
    const val CROSSED_NET = 2

    /** 공이 상대 진영에 떨어졌다 (셰이핑, `RALLY_WIN` 과 중복된다). */
    const val OPPONENT_MISS = 3

    /** 프레임당 -1 (셰이핑). 랠리를 길게 끄는 것에 값을 매기고 싶을 때 쓴다. */
    const val TIME_PENALTY = 4

    const val COUNT = 5

    /** `info` 로 나가는 이름. Python 쪽이 이 순서를 그대로 쓴다. */
    val NAMES: List<String> = listOf("rally_win", "ball_touch", "crossed_net", "opponent_miss", "time_penalty")

    /** `terms[offset until offset + COUNT]` 에 가중치를 곱해 더한다. */
    fun total(terms: FloatArray, offset: Int, w: RewardWeights): Float =
        terms[offset + RALLY_WIN] * w.rallyWin +
            terms[offset + BALL_TOUCH] * w.ballTouch +
            terms[offset + CROSSED_NET] * w.crossedNet +
            terms[offset + OPPONENT_MISS] * w.opponentMiss +
            terms[offset + TIME_PENALTY] * w.timePenalty
}
