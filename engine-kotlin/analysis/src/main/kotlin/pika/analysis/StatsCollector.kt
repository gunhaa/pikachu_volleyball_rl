package pika.analysis

import pika.env.PikaGame
import pika.env.replay.RallyOutcome
import pika.env.replay.Replay
import pika.env.replay.SeedMode

/**
 * 리플레이 재생에서 랠리 · 착지 · 터치 · 파워히트를 뽑는다. (FR-11, plan.md §6.4)
 *
 * 전부 리플레이에서 파생하므로 정의를 바꾸면 `rebuild-stats` 로 다시 계산된다.
 *
 * | 지표 | 정의 |
 * |---|---|
 * | 랠리 길이 | 랠리의 물리 프레임 수, outcome 별 (득점 / -1 truncated / -2 미완) |
 * | 착지 지점 | 득점 랠리의 착지 프레임 `ball.punchEffectX` — 득점 판정과 **같은 필드** (`PikaGame.step`) |
 * | 터치 | `isCollisionWithBallHappened` 의 false → true. 랠리 시작에서 false 로 돌아간다 (`Player.initializeForNewRound`) — `PikaEnv` 의 `ball_touch` 와 같은 정의 |
 * | 파워히트 | 터치 프레임에서 그 터치 뒤 `ball.isPowerHit == true` |
 * | 파워히트 성공 | 그 뒤 **상대가 공에 닿기 전에** 랠리가 친 쪽의 득점으로 끝남 |
 *
 * ⚠️ 파워히트는 계획(§6.4)의 "`isPowerHit` false → true" 가 아니라 **터치 기준**이다. 엔진은
 *    충돌을 처리할 때마다 `isPowerHit` 을 덮어쓰므로 (`PhysicsEngine.kt:111-113`), 파워히트를
 *    파워히트로 받아치면 값이 true → true 로 남아 전환 기준은 그 두 번째를 놓친다.
 *    한 프레임에 둘 다 닿으면 엔진은 player1 → player2 순으로 처리하므로 프레임 끝의
 *    `isPowerHit` 은 player2 의 것이다 — player2 에게만 판정한다 (드물다).
 *
 * ⚠️ `ball.sound.powerHit` 은 쓰지 않는다 — 렌더러가 소비·리셋하는 부수 효과다.
 */
class StatsCollector(private val replay: Replay) {

    class Rally(
        val idx: Int,
        val seed: Int?,
        val serverP2: Boolean,
        val outcome: Int,
        val frames: Int,
        val landingX: Int?,
        val touches: IntArray,
        val powerHits: IntArray,
    )

    class PowerHit(val rallyIdx: Int, val frame: Int, val hitter: Int, var success: Boolean)

    val rallies = mutableListOf<Rally>()
    val powerHits = mutableListOf<PowerHit>()

    private var rallyIdx = 0
    private var serverP2 = replay.firstServeIsPlayer2
    private val prevTouch = BooleanArray(2)
    private val touches = IntArray(2)
    private val hits = IntArray(2)
    private val pending = mutableListOf<PowerHit>()
    private var rallyFrames = 0
    private var finished = false

    /** [pika.env.replay.ReplayPlayer.play] 의 콜백 모양. */
    fun onFrame(game: PikaGame, @Suppress("UNUSED_PARAMETER") frameIndex: Int, scorer: Int?) {
        val p = game.physics
        val outcome = scorer
            ?: if (replay.maxRallyFrames > 0 && game.rallyFrames >= replay.maxRallyFrames) RallyOutcome.TRUNCATED else null
        observe(
            p.player1.isCollisionWithBallHappened, p.player2.isCollisionWithBallHappened,
            p.ball.isPowerHit, p.ball.punchEffectX, game.rallyFrames, outcome,
        )
    }

    /**
     * 한 프레임의 관측값 (물리 **직후**, 랠리 리셋 전). 테스트가 손으로 시나리오를 먹일 수 있게 원시값을 받는다.
     * @param outcome 이 프레임에 랠리가 끝났으면 0/1/-1, 아니면 null
     */
    fun observe(touch1: Boolean, touch2: Boolean, isPowerHit: Boolean, punchEffectX: Int, frameInRally: Int, outcome: Int?) {
        check(!finished) { "finish() 뒤에는 관측할 수 없습니다" }
        rallyFrames = frameInRally
        val now = booleanArrayOf(touch1, touch2)
        val touched = BooleanArray(2) { !prevTouch[it] && now[it] }
        for (k in 0..1) {
            if (!touched[k]) continue
            touches[k]++
            // 상대가 닿았다 — 그 전의 파워히트는 실패로 확정.
            val it = pending.iterator()
            while (it.hasNext()) {
                val ph = it.next()
                if (ph.hitter != k) {
                    ph.success = false
                    it.remove()
                }
            }
        }
        val hitter = if (touched[1]) 1 else if (touched[0]) 0 else -1
        if (hitter >= 0 && isPowerHit) {
            val ph = PowerHit(rallyIdx, frameInRally, hitter, success = false)
            powerHits += ph
            pending += ph
            hits[hitter]++
        }
        now.copyInto(prevTouch)

        if (outcome != null) closeRally(outcome, if (outcome >= 0) punchEffectX else null)
    }

    /** 기록된 프레임을 다 봤다. 잘린 게임이면 마지막 랠리를 미완(-2)으로 닫는다 (경계에서 잘렸으면 0 프레임). */
    fun finish(): StatsCollector {
        if (!finished && !replay.ended) closeRally(RallyOutcome.UNFINISHED, null)
        finished = true
        return this
    }

    private fun closeRally(outcome: Int, landingX: Int?) {
        for (ph in pending) ph.success = outcome >= 0 && outcome == ph.hitter
        pending.clear()
        val seed = if (replay.seedMode == SeedMode.RALLY) replay.seeds[rallyIdx] else null
        rallies += Rally(rallyIdx, seed, serverP2, outcome, rallyFrames, landingX, touches.copyOf(), hits.copyOf())
        if (outcome >= 0) serverP2 = outcome == 1
        rallyIdx++
        touches.fill(0)
        hits.fill(0)
        prevTouch.fill(false) // Player.initializeForNewRound 가 충돌 플래그를 내린다
        rallyFrames = 0
    }

    companion object {
        /** 착지 x 히스토그램 구간 — 8px × 54칸 (코트 폭 432). */
        const val LANDING_BIN = 8
        const val LANDING_BINS = 54

        /** 착지 x → 구간. 공은 오른쪽 끝 x = 432 에도 떨어진다 — 마지막 구간이 432 를 포함한다. */
        fun landingBin(x: Int): Int = (x / LANDING_BIN).coerceIn(0, LANDING_BINS - 1)
    }
}
