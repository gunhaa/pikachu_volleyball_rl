package pika.env

/**
 * 환경 구성. 이 값들이 곧 "어떤 문제를 푸는가" 의 정의다.
 *
 * ⚠️ 구성이 바뀌면 관측·보상이 바뀌고 P4 의 골든 체인 해시가 깨진다. 그것이 이 장치의 목적이다.
 */
data class EnvConfig(
    /** 두 진영의 구성. Track A = `(External, Fsm)`, Track B = `(External, External)`. */
    val slots: Slots = Slots.EXTERNAL_VS_FSM,

    /** 시드의 뿌리. 환경 i 의 랠리 k 는 여기서 유도된다 (plan.md §8.3). */
    val baseSeed: Int = 0,

    val winningScore: Int = 15,

    /**
     * 랠리 최대 길이. 넘으면 `truncated`. 0 이면 무제한.
     *
     * 기본 **3,000** 의 근거 (plan.md §2.3): FSM vs FSM 랠리의 p99 는 2,821 프레임이고
     * 최대는 6,011 이다. 3,000 은 그 사이다 — FSM 랠리의 1% 미만이 잘린다.
     * 더 크게 잡으면 truncation 이 희귀해져 부트스트랩 버그가 드러나지 않고,
     * 더 작게 잡으면 정상 랠리를 자른다.
     */
    val maxRallyFrames: Int = 3_000,

    /** 관측 레이아웃 플래그. */
    val obs: ObsSpec.Options = ObsSpec.Options(),

    /** 오른쪽 슬롯의 관측을 왼쪽 시점으로 뒤집는가 (plan.md §5.3). */
    val mirrorObservations: Boolean = true,

    /** 에이전트의 `powerHit` 을 엣지 트리거로 변환하는가 (FR-5). Phase 3 이 A/B 할 수 있게 끌 수 있다. */
    val edgeTriggerPowerHit: Boolean = true,

    /**
     * FSM 의 `computerBoldness` 를 이 값(0..4)으로 고정한다. **-1 이면 매 랠리 추첨(원작)이다.**
     *
     * ⚠️ 커리큘럼 손잡이가 아니라 **진단 축**이다 (PRD §2.1). 무작위 정책의 승률은 b 에
     *    대해 단조롭지 않다 — 잡음 안이다. b 가 실제로 바꾸는 것은 난이도가 아니라 랠리 길이다.
     */
    val fixedBoldness: Int = -1,

    val rewardWeights: RewardWeights = RewardWeights(),
) {
    /** 외부 정책이 행동을 주는 슬롯의 인덱스. Track A 는 `[0]`, Track B 는 `[0, 1]`. */
    val externalSlots: IntArray
        get() = (0..1).filter { !slots[it].isFsm }.toIntArray()

    /** 한 환경이 내보내는 관측/행동의 개수. */
    val slotCount: Int get() = (0..1).count { !slots[it].isFsm }

    val obsDim: Int get() = ObsSpec.dim(obs)

    /** 이 구성이 만드는 관측 레이아웃의 신원 (서버 `Health` ↔ Python 대조용). */
    val layoutHash: String get() = ObsSpec.layoutHash(obs)

    init {
        require(slotCount > 0) { "외부 정책 슬롯이 하나도 없습니다. FSM vs FSM 은 평가(GameEvaluator)의 일입니다." }
        require(winningScore > 0) { "winningScore 는 양수여야 합니다" }
        require(maxRallyFrames >= 0) { "maxRallyFrames 는 0(무제한) 이상이어야 합니다" }
        require(fixedBoldness == -1 || fixedBoldness in 0..4) {
            "fixedBoldness 는 -1(추첨) 또는 0..4 여야 합니다: $fixedBoldness"
        }
    }
}
