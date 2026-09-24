package pika.env.replay

import pika.core.PikaUserInput
import pika.core.Rand
import pika.core.XorShift32
import pika.env.ActionCodec
import pika.env.PikaGame

/**
 * 리플레이를 다시 돌리고 기록된 결과와 대조한다. (FR-3, plan.md §4.2)
 *
 * 재생은 [PikaGame] 을 **그대로** 쓴다. 규칙을 다시 짜지 않는다 — Kotlin 쪽 규칙층의 정답이
 * `PikaGame` 이고, 재생기가 규칙을 따로 들고 있으면 "기록기와 재생기가 같이 틀리는" 길이 생긴다.
 *
 * 시드를 거는 순서는 기록한 경로와 같아야 한다 (plan.md §2.1 ⚠️):
 * - 게임 시작: RNG 교체 → `PikaGame` 생성 (Player 생성자가 `rand()` 2회 소비)
 * - RALLY 규약의 다음 랠리: RNG 교체 → `startNextRally()`
 *
 * `maxRallyFrames` 는 **재생이 스스로 판정**한다. 기록된 `-1` 과 재생 판정이 다르면 실패다 (§3.3).
 */
class ReplayPlayer(val replay: Replay) {

    /** 재생 결과. [mismatch] 가 null 이면 기록과 완전히 같다. */
    class PlayResult(
        val mismatch: String?,
        val framesPlayed: Int,
        val scores: IntArray,
        val rallies: Int,
    ) {
        val ok: Boolean get() = mismatch == null

        /** @throws IllegalStateException 기록과 다를 때 */
        fun orThrow(): PlayResult {
            check(ok) { "리플레이 검증 실패: $mismatch" }
            return this
        }
    }

    /**
     * @param onFrame 매 물리 프레임 **직후** (`game.step` 다음, 랠리 리셋 전). `frameIndex` 는 0부터,
     *   `scorer` 는 `game.step` 의 반환값 (null 이 아니면 공이 땅에 닿은 프레임).
     *   통계(§6.4)와 체인 해시가 여기서 상태를 읽는다.
     */
    fun play(onFrame: (game: PikaGame, frameIndex: Int, scorer: Int?) -> Unit = { _, _, _ -> }): PlayResult {
        val r = replay
        var rng = XorShift32(r.seeds[0])
        val game = PikaGame(Rand { rng.nextRand() }, r.slots, r.winningScore, r.firstServeIsPlayer2, r.fixedBoldness)
        val inputs = arrayOf(PikaUserInput(), PikaUserInput())
        val slots = r.externalSlots

        var frame = 0
        var rally = 0

        fun fail(msg: String) = PlayResult(msg, frame, game.scores.copyOf(), rally)

        while (frame < r.frameCount) {
            for (k in slots.indices) ActionCodec.decode(r.input(k, frame), inputs[slots[k]], edge = null)
            val scorer = game.step(inputs)
            onFrame(game, frame, scorer)
            frame++

            val outcome = scorer
                ?: if (r.maxRallyFrames > 0 && game.rallyFrames >= r.maxRallyFrames) RallyOutcome.TRUNCATED else null
            if (outcome == null) {
                // 기록된 것보다 길어지는 순간 멈춘다 — 끝까지 가면 첫 어긋남이 묻힌다.
                if (rally < r.rallyCount && game.rallyFrames > r.rallyFrames[rally] &&
                    r.rallyOutcomes[rally].toInt() != RallyOutcome.UNFINISHED
                ) {
                    return fail("랠리 $rally 가 기록된 ${r.rallyFrames[rally]} 프레임 안에 끝나지 않았습니다")
                }
                continue
            }

            if (rally >= r.rallyCount) return fail("기록된 랠리 ${r.rallyCount} 개보다 많이 끝났습니다")
            val recFrames = r.rallyFrames[rally]
            val recOutcome = r.rallyOutcomes[rally].toInt()
            if (recFrames != game.rallyFrames || recOutcome != outcome) {
                return fail(
                    "랠리 $rally: 기록 (${recFrames}프레임, 결과 $recOutcome) ≠ 재생 (${game.rallyFrames}프레임, 결과 $outcome)",
                )
            }
            rally++

            if (game.gameEnded) {
                if (frame != r.frameCount) return fail("게임이 $frame 프레임에 끝났는데 기록은 ${r.frameCount} 프레임입니다")
                break
            }
            if (r.seedMode == SeedMode.RALLY) {
                if (rally >= r.seeds.size) return fail("랠리 $rally 의 시드가 없습니다")
                rng = XorShift32(r.seeds[rally])
            }
            game.startNextRally()
        }

        // 여기서부터는 기록된 프레임을 다 썼다.
        if (r.ended) {
            if (!game.gameEnded) return fail("기록은 끝난 게임인데 재생은 ${game.scores.toList()} 에서 끝나지 않았습니다")
            if (rally != r.rallyCount) return fail("랠리 수: 기록 ${r.rallyCount} ≠ 재생 $rally")
        } else {
            if (game.gameEnded) return fail("기록은 잘린 게임인데 재생은 끝났습니다")
            if (rally != r.rallyCount - 1 || game.rallyFrames != r.rallyFrames[rally]) {
                return fail("미완 랠리: 기록 (랠리 ${r.rallyCount - 1}, ${r.rallyFrames.last()}프레임) ≠ 재생 (랠리 $rally, ${game.rallyFrames}프레임)")
            }
        }
        if (!game.scores.contentEquals(r.finalScore)) {
            return fail("최종 점수: 기록 ${r.finalScore.toList()} ≠ 재생 ${game.scores.toList()}")
        }
        return PlayResult(null, frame, game.scores.copyOf(), if (r.ended) rally else rally + 1)
    }
}
