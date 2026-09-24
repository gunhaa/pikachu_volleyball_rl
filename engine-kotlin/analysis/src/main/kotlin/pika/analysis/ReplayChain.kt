package pika.analysis

import pika.conformance.StateSpec
import pika.env.replay.Replay
import pika.env.replay.ReplayPlayer

/**
 * 리플레이를 Kotlin 으로 재생하며 프레임별 상태 체인 해시를 뜬다. (M4-b, plan.md §5.3)
 *
 * 규약은 Phase 1 과 같다: `h_0 = 0³²`, `h_n = SHA256(h_{n-1} ‖ state_n)`, state 는 State Spec
 * 기본 44필드(sound 제외, little-endian int32). 상태는 `game.step` **직후 · 랠리 리셋 전** 에 읽는다.
 * JS 러너(`viewer-web/src/runner/chain.mjs`)가 같은 자리에서 같은 해시를 떠야 한다.
 *
 * sound 필드를 빼는 이유: 소리 플래그는 렌더러·오디오가 소비·리셋하는 부수 효과라서,
 * 뷰어가 붙으면 값이 달라질 수 있다. 물리 상태만 증명 대상이다.
 */
object ReplayChain {

    /** 중간 해시 간격. 첫 불일치 구간을 1,000 프레임 안으로 좁힌다. */
    const val INTERVAL: Int = 1000

    class Result(
        /** 최종 체인 (hex 64). */
        val finalHex: String,
        /** `INTERVAL` 프레임마다의 체인 (1000, 2000, … 프레임 **후**). */
        val checkpoints: List<String>,
        val play: ReplayPlayer.PlayResult,
    )

    fun compute(replay: Replay): Result {
        val digest = StateSpec.sha256()
        val ints = IntArray(StateSpec.intCount(strict = false))
        val bytes = ByteArray(ints.size * 4)
        var chain = StateSpec.chainSeed()
        val checkpoints = mutableListOf<String>()
        val play = ReplayPlayer(replay).play { game, frame, scorer ->
            StateSpec.writeInts(game.physics, scorer != null, strict = false, out = ints)
            StateSpec.packInts(ints, bytes)
            chain = StateSpec.chainStep(digest, chain, bytes)
            if ((frame + 1) % INTERVAL == 0) checkpoints += StateSpec.toHex(chain)
        }
        return Result(StateSpec.toHex(chain), checkpoints, play)
    }
}
