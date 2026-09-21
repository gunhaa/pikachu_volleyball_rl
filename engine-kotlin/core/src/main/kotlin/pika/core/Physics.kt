package pika.core

/**
 * `upstream/src/resources/js/physics.js` 의 Kotlin 포팅.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 정수 의미 보존 규칙 (plan.md §3)
 * ─────────────────────────────────────────────────────────────────────────────
 * 원본은 1997년 기계어를 옮긴 코드라 **모든 상태가 32비트 정수**다.
 * 나눗셈 7곳은 전부 `(a / b) | 0` 으로 즉시 절삭되고, `Math` 는 `abs` 만 쓰며,
 * 소수점 리터럴은 0건이다.
 *
 *   - 상태 필드는 전부 `Int`. **`Long` 금지** — `| 0` 의 int32 래핑까지 재현해야 한다.
 *   - JS `(a / b) | 0` ↔ Kotlin `a / b`  (둘 다 0 방향 절삭)
 *   - JS `a % b`       ↔ Kotlin `a % b`  (둘 다 피제수 부호를 따름)
 *   - JS `Math.abs(x)` ↔ Kotlin `kotlin.math.abs(x)`
 *
 * 함수 위 주석의 `FUN_00403dd0` 은 원작 기계어의 주소다. 업스트림 주석을 따른다.
 */

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 경기장 너비 */
const val GROUND_WIDTH = 432

/** 경기장 절반 너비. 네트 기둥의 x 좌표이기도 하다. */
const val GROUND_HALF_WIDTH = GROUND_WIDTH / 2 // 216

/** 플레이어(피카츄) 길이: 너비 = 높이 = 64 */
const val PLAYER_LENGTH = 64

/** 플레이어 절반 길이 */
const val PLAYER_HALF_LENGTH = PLAYER_LENGTH / 2 // 32

/** 플레이어가 땅에 닿아 있을 때의 y 좌표 */
const val PLAYER_TOUCHING_GROUND_Y_COORD = 244

/** 공의 반지름 */
const val BALL_RADIUS = 20

/** 공이 땅에 닿아 있을 때의 y 좌표 */
const val BALL_TOUCHING_GROUND_Y_COORD = 252

/** 네트 기둥 절반 너비 (스프라이트 픽셀이 아니라 이 물리 엔진 전용 값) */
const val NET_PILLAR_HALF_WIDTH = 25

/** 네트 기둥 상단의 윗변 y 좌표 */
const val NET_PILLAR_TOP_TOP_Y_COORD = 176

/** 네트 기둥 상단의 아랫변 y 좌표 (이 물리 엔진 전용 값) */
const val NET_PILLAR_TOP_BOTTOM_Y_COORD = 192

/**
 * 무한 루프의 반복 횟수 상한.
 *
 * 원작 기계어에는 없는 값이다. 원래 공 x 범위 [20, 432] 에서는
 * [calculateExpectedLandingPointXFor] 와 [expectedLandingPointXWhenPowerHit] 의
 * 루프가 항상 곧 끝나지만, 범위를 바꾸면 끝나지 않는 경우가 관측되어 안전장치로 들어갔다.
 */
const val INFINITE_LOOP_LIMIT = 1000

// ─────────────────────────────────────────────────────────────────────────────
// RNG 주입
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 업스트림 `rand.js` 의 `rand()` 에 대응한다. **[0, 32767] 정수**를 반환해야 한다.
 *
 * JS 는 모듈 전역 `customRng` 를 쓰지만 여기서는 주입한다.
 * 소비 지점과 순서가 같으면 전역이든 주입이든 결과는 동일하다.
 */
fun interface Rand {
    /** @return [0, 32767] 범위의 정수 */
    fun next(): Int
}

// ─────────────────────────────────────────────────────────────────────────────
// 상태
// ─────────────────────────────────────────────────────────────────────────────

/** 키보드·조이스틱 등에서 온 사용자 입력. */
class PikaUserInput {
    /** 0: 없음, -1: 왼쪽, 1: 오른쪽 */
    var xDirection: Int = 0

    /** 0: 없음, -1: 위, 1: 아래 */
    var yDirection: Int = 0

    /** 0: 자동 반복이거나 입력 없음, 1: 자동 반복이 아닌 파워히트 입력 */
    var powerHit: Int = 0
}

/** 플레이어 효과음. 물리에 영향은 없지만 물리 엔진이 세팅한다 (엄격 모드 비교 대상). */
class PlayerSound {
    var pipikachu: Boolean = false
    var pika: Boolean = false
    var chu: Boolean = false
}

/** 공 효과음. */
class BallSound {
    var powerHit: Boolean = false
    var ballTouchesGround: Boolean = false
}

/**
 * 플레이어.
 *
 * 초기값 출처: `FUN_000403a90` && `FUN_00401f40`
 *
 * ⚠️ 업스트림 생성자는 [initializeForNewRound] 를 먼저 호출하고 **그 뒤에**
 *    [divingDirection] · [lyingDownDurationLeft] · [isWinner] · [gameEnded] ·
 *    [computerWhereToStandBy] 를 세팅한다. 즉 이 5개는 **라운드 리셋 대상이 아니다.**
 *    "리셋 함수니까 전부 초기화" 로 정리하면 라운드를 건너 조용히 갈라진다.
 *
 * @param isPlayer2 오른쪽 플레이어인가?
 * @param isComputer 컴퓨터가 조종하는가?
 * @param rand RNG. [computerBoldness] 추첨과 FSM 의사결정에 쓰인다.
 */
class Player(
    val isPlayer2: Boolean,      // 0xA0
    val isComputer: Boolean,     // 0xA4
    private val rand: Rand,
) {
    // ── 라운드 리셋 대상 ──────────────────────────────────────────────
    /** x 좌표. player1 은 36, player2 는 396 */
    var x: Int = 0               // 0xA8

    /** y 좌표 */
    var y: Int = 0               // 0xAC

    /** y 방향 속도 */
    var yVelocity: Int = 0       // 0xB0

    var isCollisionWithBallHappened: Boolean = false // 0xBC

    /**
     * 0: normal, 1: jumping, 2: jumping_and_power_hitting, 3: diving,
     * 4: lying_down_after_diving, 5: win!, 6: lost..
     */
    var state: Int = 0           // 0xC0

    var frameNumber: Int = 0     // 0xC4

    var normalStatusArmSwingDirection: Int = 1 // 0xC8

    var delayBeforeNextFrame: Int = 0          // 0xCC

    /**
     * 라운드 시작마다 `rand() % 5` 로 재추첨된다. 클수록 대담하다.
     * 크면 공이 상대편에 있다고 더 많이 판단하고, 예상 착지점까지의 거리를 크게 잡고,
     * 더 많이 점프하고, 덜 다이빙한다.
     */
    var computerBoldness: Int = 0              // 0xD8

    // ── 라운드 리셋 대상이 아닌 것 ─────────────────────────────────────
    /** -1: 왼쪽, 0: 다이빙 아님, 1: 오른쪽 */
    var divingDirection: Int = 0               // 0xB4

    var lyingDownDurationLeft: Int = -1        // 0xB8

    var isWinner: Boolean = false              // 0xD0

    var gameEnded: Boolean = false             // 0xD4

    /**
     * 공이 상대편에 머무를 때 [letComputerDecideUserInput] 이 0/1 로 무작위 반전시킨다.
     * 0 이면 자기 진영 중앙 부근, 1 이면 네트 옆에 선다.
     */
    var computerWhereToStandBy: Int = 0        // 0xDC

    /** 원본에는 없는 필드. 스테레오 사운드용. */
    val sound: PlayerSound = PlayerSound()

    init {
        initializeForNewRound()
    }

    /** 새 라운드 초기화. ⚠️ 위 "리셋 대상이 아닌 것" 5개는 건드리지 않는다. */
    fun initializeForNewRound() {
        x = if (isPlayer2) GROUND_WIDTH - 36 else 36
        y = PLAYER_TOUCHING_GROUND_Y_COORD
        yVelocity = 0
        isCollisionWithBallHappened = false
        state = 0
        frameNumber = 0
        normalStatusArmSwingDirection = 1
        delayBeforeNextFrame = 0
        computerBoldness = rand.next() % 5
    }
}

/**
 * 공.
 *
 * 초기값 출처: `FUN_000403a90` && `FUN_00402d60`
 *
 * ⚠️ [Player] 와 마찬가지로 [expectedLandingPointX] · [rotation] · [fineRotation] ·
 *    [punchEffectX] · [punchEffectY] 와 previous* 는 **라운드 리셋 대상이 아니다.**
 *
 * @param isPlayer2Serve 이번 라운드에 player2 가 서브하는가?
 */
class Ball(isPlayer2Serve: Boolean) {
    // ── 라운드 리셋 대상 ──────────────────────────────────────────────
    var x: Int = 0                    // 0x30
    var y: Int = 0                    // 0x34
    var xVelocity: Int = 0            // 0x38
    var yVelocity: Int = 0            // 0x3C
    var punchEffectRadius: Int = 0    // 0x4C
    var isPowerHit: Boolean = false   // 0x68

    // ── 라운드 리셋 대상이 아닌 것 ─────────────────────────────────────
    /** 예상 착지점의 x 좌표 */
    var expectedLandingPointX: Int = 0 // 0x40

    /** 공 회전 프레임 선택자. 5 에 계속 머무르면 hyper ball 글리치가 난다. */
    var rotation: Int = 0             // 0x44

    var fineRotation: Int = 0         // 0x48

    /** 펀치 이펙트 x 좌표. 라운드 종료 판정이 이 값을 읽는다. */
    var punchEffectX: Int = 0         // 0x50

    /** 펀치 이펙트 y 좌표 */
    var punchEffectY: Int = 0         // 0x54

    // 파워히트 잔상 효과용
    var previousX: Int = 0            // 0x58
    var previousPreviousX: Int = 0    // 0x5C
    var previousY: Int = 0            // 0x60
    var previousPreviousY: Int = 0    // 0x64

    /** 원본에는 없는 필드. 스테레오 사운드용. */
    val sound: BallSound = BallSound()

    init {
        initializeForNewRound(isPlayer2Serve)
    }

    /** 새 라운드 초기화. */
    fun initializeForNewRound(isPlayer2Serve: Boolean) {
        x = if (isPlayer2Serve) GROUND_WIDTH - 56 else 56
        y = 0
        xVelocity = 0
        yVelocity = 1
        punchEffectRadius = 0
        isPowerHit = false
    }
}

/**
 * 물리 객체 묶음. [physicsEngine] 이 이 값들을 계산해 채운다.
 *
 * ⚠️ 생성 순서가 RNG 소비 순서를 결정한다: player1 → player2 → ball.
 *    (ball 생성자는 RNG 를 소비하지 않는다.)
 */
class PikaPhysics(
    isPlayer1Computer: Boolean,
    isPlayer2Computer: Boolean,
    rand: Rand,
) {
    val player1: Player = Player(isPlayer2 = false, isComputer = isPlayer1Computer, rand = rand)
    val player2: Player = Player(isPlayer2 = true, isComputer = isPlayer2Computer, rand = rand)
    val ball: Ball = Ball(isPlayer2Serve = false)

    private val engine = PhysicsEngine(rand)

    /**
     * 다음 프레임의 물리 값을 계산한다.
     *
     * @param userInputArray [0] player1 입력, [1] player2 입력
     * @return 공이 땅에 닿았는가?
     */
    fun runEngineForNextFrame(userInputArray: Array<PikaUserInput>): Boolean =
        engine.run(player1, player2, ball, userInputArray)
}
