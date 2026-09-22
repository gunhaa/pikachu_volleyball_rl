package pika.conformance

import pika.core.GROUND_HALF_WIDTH
import pika.core.Ball
import pika.core.PikaPhysics
import pika.core.PikaUserInput
import pika.core.Rand
import pika.core.XorShift32

/**
 * 에피소드 하네스 — `tools/js-oracle/harness.mjs` 의 Kotlin 짝이다.
 *
 * 고정 T 프레임을 돌리고 랠리가 끝나면 라운드를 리셋해 계속 진행한다. (plan.md §6.2)
 *
 * ⚠️ 이것은 하네스 코드지 `pikavolley.js` 의 포팅이 아니다.
 *    점수·게임종료·슬로모션은 다루지 않는다 (ROADMAP Phase 2).
 */
object Harness {

    /** 입력 스트림 시드를 물리 스트림 시드에서 갈라낸다. JS 의 `INPUT_SEED_SALT` 와 같은 값. */
    const val INPUT_SEED_SALT: Int = -1640531527 // 0x9E3779B9

    /**
     * 어느 쪽이 다음 서브를 하는가.
     *
     * 업스트림은 `ball.x` 가 아니라 `ball.punchEffectX` 를 읽는다 (pikavolley.js:374).
     *
     * @return true 면 player2 가 서브 (= player2 가 득점)
     */
    fun nextServeIsPlayer2(ball: Ball): Boolean = ball.punchEffectX < GROUND_HALF_WIDTH

    /** 라운드 리셋. 순서가 RNG 소비 순서를 결정한다: player1 → player2 → ball. */
    fun resetRound(physics: PikaPhysics, isPlayer2Serve: Boolean) {
        physics.player1.initializeForNewRound()
        physics.player2.initializeForNewRound()
        physics.ball.initializeForNewRound(isPlayer2Serve)
    }

    /**
     * 에피소드 하나를 돌린다.
     *
     * @param onCreate [PikaPhysics] 생성 직후·프레임 0 이전에 상태를 심는다 (표적 케이스 (d) 용).
     *   ⚠️ RNG 는 이미 주입된 뒤이고 생성자가 `rand()` 를 소비한 뒤다. 여기서 `rand()` 를 쓰면 안 된다.
     * @param onFrame (physics, isBallTouchingGround, inputs, frameIndex) — 리셋 **전** 상태로 호출된다
     */
    fun runEpisode(
        seed: Int,
        frames: Int,
        gen: Generator,
        onCreate: (PikaPhysics) -> Unit = {},
        onFrame: (PikaPhysics, Boolean, Array<PikaUserInput>, Int) -> Unit,
    ) {
        // RNG 주입은 반드시 생성자 호출보다 먼저. Player 생성자가 이미 rand() 를 소비한다.
        val physicsRng = XorShift32(seed)
        val rand = Rand { physicsRng.nextRand() }

        val inputRng = XorShift32(seed xor INPUT_SEED_SALT)
        val byComputer = gen.isComputerControlled

        val physics = PikaPhysics(byComputer, byComputer, rand)
        onCreate(physics)
        val inputs = arrayOf(PikaUserInput(), PikaUserInput())

        for (f in 0 until frames) {
            InputGenerators.fill(gen, inputRng, inputs, physics)
            val isBallTouchingGround = physics.runEngineForNextFrame(inputs)

            // 해시는 리셋 전 상태로 뜬다. 리셋 효과는 다음 프레임에서 검증된다.
            onFrame(physics, isBallTouchingGround, inputs, f)

            if (isBallTouchingGround) {
                resetRound(physics, nextServeIsPlayer2(physics.ball))
            }
        }
    }

    /**
     * 표적 케이스 하나를 돌린다. (plan.md §6.4)
     *
     * 케이스 번호가 하네스의 "시드" 자리에 들어간다. 프레임 수·입력 생성기·초기 상태는
     * `tools/targeted-cases.txt` 가 정하고, JS 오라클도 **같은 파일**을 읽는다.
     *
     * @param index 케이스 번호 (1-based)
     */
    fun runTargetedCase(
        index: Int,
        onFrame: (PikaPhysics, Boolean, Array<PikaUserInput>, Int) -> Unit,
    ) {
        val case = TargetedCases.at(index)
        runEpisode(case.seed, case.frames, case.gen, TargetedCases.setupFor(case), onFrame)
    }

    /**
     * 라운드 리셋만 검증하는 프로브. **physicsEngine 을 한 번도 돌리지 않는다.**
     *
     * `initializeForNewRound` 은 엔진과 독립이므로, 엔진 포팅이 끝나기 전에도
     * 리셋 순서와 RNG 소비 패턴을 검증할 수 있다.
     * 서브권은 물리에 의존하지 않도록 `i % 2 == 0` 으로 고정한다.
     *
     * @param onState (physics, resetIndex) — 리셋 **전** 상태로 호출된다
     */
    fun runResetProbe(
        seed: Int,
        resets: Int,
        gen: Generator,
        onState: (PikaPhysics, Int) -> Unit,
    ) {
        val physicsRng = XorShift32(seed)
        val rand = Rand { physicsRng.nextRand() }
        val byComputer = gen.isComputerControlled
        val physics = PikaPhysics(byComputer, byComputer, rand)

        for (i in 0 until resets) {
            onState(physics, i)
            resetRound(physics, i % 2 == 0)
        }
    }
}
