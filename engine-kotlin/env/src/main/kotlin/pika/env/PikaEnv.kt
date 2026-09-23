package pika.env

import pika.core.GROUND_HALF_WIDTH
import pika.core.PikaUserInput
import pika.core.Rand
import pika.core.XorShift32

/**
 * RL 환경 하나. **에피소드 = 랠리**다. (FR-7, FR-9)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * next-step autoreset (Gymnasium 1.x, plan.md §8.2)
 * ─────────────────────────────────────────────────────────────────────────────
 * | 스텝 | 돌려주는 obs | terminated | 내부 |
 * |---|---|---|---|
 * | t   | 랠리의 **마지막** 관측 | 1 | 리셋하지 **않는다** |
 * | t+1 | **새 랠리의 첫** 관측  | 0 | 이 호출 시작 시점에 리셋. 이 스텝의 행동은 **버린다** |
 *
 * ⚠️ 구 API 의 same-step autoreset 으로 만들면 PPO 가 "종료 상태의 value" 자리에
 *    새 에피소드의 첫 관측을 쓰게 된다. 학습은 계속 돌아가고 숫자도 나온다 —
 *    **조용히** 틀린다. 그래서 전용 테스트로 못 박는다 (M2-c).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 시드 (plan.md §8.3)
 * ─────────────────────────────────────────────────────────────────────────────
 * 환경 i 의 랠리 k 는 `XorShift32(deriveSeed(baseSeed, i, k))` 로 시작한다.
 * 벡터 크기가 1 이든 256 이든 **환경 i 의 수열이 바뀌지 않는다** (M2-d).
 * 벡터 크기로 시드를 나눠 쓰면 N 을 바꾸는 순간 실험이 재현되지 않는다.
 *
 * @param envIndex 벡터 안에서의 위치. 시드 유도에 들어간다.
 */
class PikaEnv(val config: EnvConfig, val envIndex: Int = 0) {

    val encoder: ObsEncoder = ObsEncoder(config.obs, config.mirrorObservations, config.winningScore)
    val obsDim: Int = encoder.dim

    /** 외부 정책 슬롯. 관측·행동·보상은 이 순서를 따른다. */
    private val externalSlots: IntArray = config.externalSlots
    val slotCount: Int = externalSlots.size

    private var rng: XorShift32 = XorShift32(1)
    private val rand = Rand { rng.nextRand() }

    /** 재사용한다. 스텝마다 할당하지 않는다 (plan.md §11). */
    private val inputs: Array<PikaUserInput> = arrayOf(PikaUserInput(), PikaUserInput())

    /** 슬롯별 엣지 변환기. 랠리 경계에서 리셋된다 — 테스트가 그것을 본다. */
    internal val edges: Array<EdgeTrigger> = arrayOf(EdgeTrigger(), EdgeTrigger())

    lateinit var game: PikaGame
        private set

    /** 이 환경이 시작한 이래의 랠리 번호. 게임을 넘어서도 계속 증가한다 (시드 유도의 k). */
    var rallyCounter: Int = 0
        private set

    /** 끝낸 게임 수. 새 게임의 첫 서브를 번갈아 주는 데 쓴다. */
    var gameCounter: Int = 0
        private set

    /** 다음 스텝 시작 시점에 리셋해야 하는가 (next-step autoreset). */
    var pendingReset: Boolean = false
        private set

    // ── 보상 항 계산에 필요한 직전 상태 ────────────────────────────────
    private var lastToucher: Int = -1

    init {
        resetAll()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 리셋
    // ─────────────────────────────────────────────────────────────────────────

    /** 환경을 처음 상태로 되돌리고 첫 관측을 쓴다. 같은 `config` 면 같은 결과다 (M2-d). */
    fun reset(obs: FloatArray, obsOffset: Int) {
        resetAll()
        writeObs(obs, obsOffset)
    }

    private fun resetAll() {
        rallyCounter = 0
        gameCounter = 0
        pendingReset = false
        startGame()
    }

    private fun startGame() {
        rng = XorShift32(deriveSeed(config.baseSeed, envIndex, rallyCounter))
        game = PikaGame(
            rand = rand,
            slots = config.slots,
            winningScore = config.winningScore,
            // 첫 서브를 게임마다 번갈아 준다. 난수를 쓰지 않는 이유는 그 한 번의 소비가
            // 랠리 시드의 의미(=오직 (baseSeed, i, k) 의 함수) 를 흐리기 때문이다.
            firstServeIsPlayer2 = gameCounter % 2 == 1,
            fixedBoldness = FixedBoldness(config.fixedBoldness),
        )
        gameCounter++
        beginRally()
    }

    /** 랠리 경계에서 초기화되어야 하는 것들. */
    private fun beginRally() {
        // ⚠️ 엣지 트리거를 리셋하지 않으면 이전 랠리 마지막 프레임의 키 상태가 새어 들어간다.
        edges[0].reset()
        edges[1].reset()
        lastToucher = -1
        // 리셋 직후 입력은 중립이다. FSM 슬롯의 값은 어차피 엔진이 덮어쓴다.
        for (input in inputs) {
            input.xDirection = 0
            input.yDirection = 0
            input.powerHit = 0
        }
    }

    /** 다음 에피소드(랠리) 로 넘어간다. 게임이 끝났으면 새 게임을 시작한다. */
    private fun advanceRally() {
        rallyCounter++
        if (game.gameEnded) {
            startGame()
        } else {
            // 랠리마다 시드를 다시 잡는다 — 그래야 환경 i 의 랠리 k 가 벡터 크기와 무관해진다.
            rng = XorShift32(deriveSeed(config.baseSeed, envIndex, rallyCounter))
            game.startNextRally()
            beginRally()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 스텝
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 한 프레임 진행한다.
     *
     * @param actions 슬롯당 1바이트 (`Discrete(18)`). `[actionOffset, actionOffset + slotCount)`
     * @param obs 관측을 쓸 곳. `slotCount × obsDim` 칸을 쓴다
     * @param rewards 가중 합계. `slotCount` 칸
     * @param terms 항별 값. `slotCount × RewardTerms.COUNT` 칸 (info 용)
     * @return 비트 플래그 — `1` terminated, `2` truncated
     */
    fun step(
        actions: ByteArray,
        actionOffset: Int,
        obs: FloatArray,
        obsOffset: Int,
        rewards: FloatArray,
        rewardOffset: Int,
        terms: FloatArray,
        termsOffset: Int,
    ): Int {
        // (1) next-step autoreset — 이 스텝의 행동은 버린다.
        if (pendingReset) {
            advanceRally()
            pendingReset = false
            writeObs(obs, obsOffset)
            for (k in 0 until slotCount) rewards[rewardOffset + k] = 0f
            for (k in 0 until slotCount * RewardTerms.COUNT) terms[termsOffset + k] = 0f
            return 0
        }

        // (2) 행동 → 엔진 입력. FSM 슬롯은 건드리지 않는다 (엔진이 덮어쓴다).
        val edge = if (config.edgeTriggerPowerHit) edges else null
        for (k in externalSlots.indices) {
            val slot = externalSlots[k]
            val action = actions[actionOffset + k].toInt() and 0xFF
            ActionCodec.decode(action, inputs[slot], edge?.get(slot))
        }

        // (3) 직전 상태 — 보상 항이 "이번 프레임에 일어난 일" 을 알려면 필요하다.
        val physics = game.physics
        val touchedBefore0 = physics.player1.isCollisionWithBallHappened
        val touchedBefore1 = physics.player2.isCollisionWithBallHappened
        val ballXBefore = physics.ball.x

        // (4) 물리 한 프레임
        val scorer = game.step(inputs)

        // (5) 보상 항
        // ⚠️ 네트 통과 판정이 먼저다. 엔진은 공을 **먼저** 움직이고 그 다음 충돌을 처리하므로
        //    (PhysicsEngine.run 의 호출 순서), 이번 프레임의 이동은 직전 프레임의 타격 결과다.
        val crossedBy = crossedNetBy(ballXBefore, physics.ball.x)
        val touched0 = !touchedBefore0 && physics.player1.isCollisionWithBallHappened
        val touched1 = !touchedBefore1 && physics.player2.isCollisionWithBallHappened
        if (touched0) lastToucher = 0
        if (touched1) lastToucher = 1

        val terminated = scorer != null
        val truncated = !terminated && config.maxRallyFrames > 0 && game.rallyFrames >= config.maxRallyFrames

        for (k in externalSlots.indices) {
            val slot = externalSlots[k]
            val base = termsOffset + k * RewardTerms.COUNT
            terms[base + RewardTerms.RALLY_WIN] = when (scorer) {
                null -> 0f
                slot -> 1f
                else -> -1f
            }
            terms[base + RewardTerms.BALL_TOUCH] = if (if (slot == 0) touched0 else touched1) 1f else 0f
            terms[base + RewardTerms.CROSSED_NET] = if (crossedBy == slot) 1f else 0f
            terms[base + RewardTerms.OPPONENT_MISS] = if (scorer == slot) 1f else 0f
            terms[base + RewardTerms.TIME_PENALTY] = -1f
            rewards[rewardOffset + k] = RewardTerms.total(terms, base, config.rewardWeights)
        }

        // (6) 관측은 **리셋 전** 상태다. 랠리의 마지막 관측이 여기서 나간다 (§8.2 의 t 행).
        writeObs(obs, obsOffset)

        // (7) 플래그
        pendingReset = terminated || truncated
        return (if (terminated) 1 else 0) or (if (truncated) 2 else 0)
    }

    /**
     * 이번 프레임에 네트를 넘어 **상대 진영으로** 간 공의 주인. 없으면 -1.
     *
     * 마지막으로 공을 친 쪽에게만 준다. 내 진영으로 넘어온 공은 상대의 [RewardTerms.CROSSED_NET] 이다.
     */
    private fun crossedNetBy(xBefore: Int, xAfter: Int): Int = when {
        lastToucher == 0 && xBefore < GROUND_HALF_WIDTH && xAfter >= GROUND_HALF_WIDTH -> 0
        lastToucher == 1 && xBefore >= GROUND_HALF_WIDTH && xAfter < GROUND_HALF_WIDTH -> 1
        else -> -1
    }

    private fun writeObs(obs: FloatArray, obsOffset: Int) {
        for (k in externalSlots.indices) {
            encoder.encode(game, externalSlots[k], obs, obsOffset + k * obsDim)
        }
    }

    /** 현재 점수 `[player1, player2]`. 슬롯 시점이 아니라 진영 순서다. */
    val scores: IntArray get() = game.scores

    companion object {
        /**
         * 환경 i 의 랠리 k 의 시드.
         *
         * 암호학적 성질은 필요 없다. 필요한 것은 (1) 세 입력 중 하나만 바뀌어도 수열이
         * 완전히 갈라질 것, (2) 벡터 크기와 무관할 것 뿐이다. murmur3 의 finalizer 를 쓴다.
         */
        fun deriveSeed(baseSeed: Int, envIndex: Int, rallyIndex: Int): Int {
            var h = baseSeed
            h = fmix32(h xor (envIndex + 1) * -1640531527)   // 0x9E3779B9
            h = fmix32(h xor (rallyIndex + 1) * -2048144789) // 0x85EBCA6B
            return h
        }

        private fun fmix32(input: Int): Int {
            var h = input
            h = h xor (h ushr 16)
            h *= -2048144789 // 0x85EBCA6B
            h = h xor (h ushr 13)
            h *= -1028477387 // 0xC2B2AE35
            h = h xor (h ushr 16)
            return h
        }
    }
}
