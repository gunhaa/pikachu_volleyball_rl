package pika.core

import kotlin.math.abs

/**
 * FUN_00403dd0 — 피카츄 발리볼 물리 엔진.
 *
 * `upstream/src/resources/js/physics.js` 의 함수들을 그대로 옮긴다.
 * 함수 이름·인자 순서·문장 순서를 원본과 맞춰 둔 것은 취향이 아니라 **감사(audit) 가능성** 때문이다.
 * 불일치가 났을 때 두 파일을 나란히 놓고 읽을 수 있어야 한다.
 *
 * 정수 의미 보존 규칙은 [Physics.kt][GROUND_WIDTH] 상단 주석(plan.md §3) 을 따른다.
 * JS 의 `(a / b) | 0` 은 Kotlin 의 `a / b` 와 같다 (둘 다 0 방향 절삭).
 *
 * `rand` 를 쓰는 함수만 이 클래스의 멤버다. 나머지는 순수 함수라 top-level 로 둔다.
 */
internal class PhysicsEngine(private val rand: Rand) {

    /**
     * 한 프레임의 물리를 계산한다.
     *
     * ⚠️ 호출 순서가 곧 사양이다.
     *    (1) 공↔세계 충돌 → (2) 플레이어별 착지점 계산 + 이동 → (3) 플레이어별 공 충돌.
     *    2단계가 플레이어마다 `calculateExpectedLandingPointXFor` 를 **다시** 부르므로
     *    `ball.expectedLandingPointX` 는 프레임당 두 번 갱신된다. 한 번으로 줄이면 갈라진다.
     *
     * @return 공이 땅에 닿았는가?
     */
    fun run(
        player1: Player,
        player2: Player,
        ball: Ball,
        userInputArray: Array<PikaUserInput>,
    ): Boolean {
        val isBallTouchingGround = processCollisionBetweenBallAndWorldAndSetBallPosition(ball)

        for (i in 0 until 2) {
            val player = if (i == 0) player1 else player2
            val theOtherPlayer = if (i == 0) player2 else player1

            // FUN_00402d90 / FUN_00402810 은 업스트림에서 생략됐다.
            // 다만 FUN_00402d90 안의 착지점 계산만은 남아 있어야 한다.
            calculateExpectedLandingPointXFor(ball)

            processPlayerMovementAndSetPlayerPosition(player, userInputArray[i], theOtherPlayer, ball)
        }

        for (i in 0 until 2) {
            val player = if (i == 0) player1 else player2

            val isHappened = isCollisionBetweenBallAndPlayerHappened(ball, player.x, player.y)
            if (isHappened) {
                // 한 번 닿은 뒤 떨어지기 전까지는 다시 처리하지 않는다 (연타 방지).
                if (!player.isCollisionWithBallHappened) {
                    processCollisionBetweenBallAndPlayer(ball, player.x, userInputArray[i], player.state)
                    player.isCollisionWithBallHappened = true
                }
            } else {
                player.isCollisionWithBallHappened = false
            }
        }

        return isBallTouchingGround
    }

    /**
     * FUN_004030a0 — 공과 플레이어의 충돌 처리.
     *
     * 속도와 예상 착지점만 세팅한다. **위치는 건드리지 않는다**
     * (위치는 [processCollisionBetweenBallAndWorldAndSetBallPosition] 담당).
     */
    private fun processCollisionBetweenBallAndPlayer(
        ball: Ball,
        playerX: Int,
        userInput: PikaUserInput,
        playerState: Int,
    ) {
        // 피카츄와 공의 x 차이가 클수록 공의 x 속도가 커진다.
        if (ball.x < playerX) {
            ball.xVelocity = -(abs(ball.x - playerX) / 3)
        } else if (ball.x > playerX) {
            ball.xVelocity = abs(ball.x - playerX) / 3
        }

        // x 속도가 0 이면 -1, 0, 1 중 무작위. ⚠️ RNG 소비 지점.
        if (ball.xVelocity == 0) {
            ball.xVelocity = (rand.next() % 3) - 1
        }

        val ballAbsYVelocity = abs(ball.yVelocity)
        ball.yVelocity = -ballAbsYVelocity

        if (ballAbsYVelocity < 15) {
            ball.yVelocity = -15
        }

        // 점프 + 파워히트 중이면
        if (playerState == 2) {
            if (ball.x < GROUND_HALF_WIDTH) {
                ball.xVelocity = (abs(userInput.xDirection) + 1) * 10
            } else {
                ball.xVelocity = -(abs(userInput.xDirection) + 1) * 10
            }
            ball.punchEffectX = ball.x
            ball.punchEffectY = ball.y

            ball.yVelocity = abs(ball.yVelocity) * userInput.yDirection * 2
            ball.punchEffectRadius = BALL_RADIUS
            ball.sound.powerHit = true

            ball.isPowerHit = true
        } else {
            ball.isPowerHit = false
        }

        calculateExpectedLandingPointXFor(ball)
    }

    /**
     * FUN_00401fc0 — 사용자 입력에 따라 플레이어를 움직이고 위치를 세팅한다.
     */
    private fun processPlayerMovementAndSetPlayerPosition(
        player: Player,
        userInput: PikaUserInput,
        theOtherPlayer: Player,
        ball: Ball,
    ) {
        if (player.isComputer) {
            letComputerDecideUserInput(player, ball, theOtherPlayer, userInput)
        }

        // 다이빙 후 누워 있으면 움직이지 않는다.
        if (player.state == 4) {
            player.lyingDownDurationLeft += -1
            if (player.lyingDownDurationLeft < -1) {
                player.state = 0
            }
            return
        }

        // x 방향 이동
        var playerVelocityX = 0
        if (player.state < 5) {
            if (player.state < 3) {
                playerVelocityX = userInput.xDirection * 6
            } else {
                // state == 3, 다이빙 중
                playerVelocityX = player.divingDirection * 8
            }
        }

        val futurePlayerX = player.x + playerVelocityX
        player.x = futurePlayerX

        // x 방향 경계 처리. ⚠️ 비교는 clamp 이전 값(futurePlayerX) 으로 한다.
        if (!player.isPlayer2) {
            if (futurePlayerX < PLAYER_HALF_LENGTH) {
                player.x = PLAYER_HALF_LENGTH
            } else if (futurePlayerX > GROUND_HALF_WIDTH - PLAYER_HALF_LENGTH) {
                player.x = GROUND_HALF_WIDTH - PLAYER_HALF_LENGTH
            }
        } else {
            if (futurePlayerX < GROUND_HALF_WIDTH + PLAYER_HALF_LENGTH) {
                player.x = GROUND_HALF_WIDTH + PLAYER_HALF_LENGTH
            } else if (futurePlayerX > GROUND_WIDTH - PLAYER_HALF_LENGTH) {
                player.x = GROUND_WIDTH - PLAYER_HALF_LENGTH
            }
        }

        // 점프
        if (player.state < 3 &&
            userInput.yDirection == -1 && // 위 입력
            player.y == PLAYER_TOUCHING_GROUND_Y_COORD // 땅에 닿아 있을 때만
        ) {
            player.yVelocity = -16
            player.state = 1
            player.frameNumber = 0
            player.sound.chu = true
        }

        // 중력
        val futurePlayerY = player.y + player.yVelocity
        player.y = futurePlayerY
        if (futurePlayerY < PLAYER_TOUCHING_GROUND_Y_COORD) {
            player.yVelocity += 1
        } else if (futurePlayerY > PLAYER_TOUCHING_GROUND_Y_COORD) {
            // 착지
            player.yVelocity = 0
            player.y = PLAYER_TOUCHING_GROUND_Y_COORD
            player.frameNumber = 0
            if (player.state == 3) {
                // 다이빙 중이었다면 눕는다
                player.state = 4
                player.frameNumber = 0
                player.lyingDownDurationLeft = 3
            } else {
                player.state = 0
            }
        }

        if (userInput.powerHit == 1) {
            if (player.state == 1) {
                // 점프 중이면 파워히트
                player.delayBeforeNextFrame = 5
                player.frameNumber = 0
                player.state = 2
                player.sound.pika = true
            } else if (player.state == 0 && userInput.xDirection != 0) {
                // 서 있고 좌우 입력이 있으면 다이빙
                player.state = 3
                player.frameNumber = 0
                player.divingDirection = userInput.xDirection
                player.yVelocity = -5
                player.sound.chu = true
            }
        }

        // 애니메이션 프레임 진행. 렌더링용처럼 보이지만 state 전이(2 → 1) 를 포함하므로 물리다.
        if (player.state == 1) {
            player.frameNumber = (player.frameNumber + 1) % 3
        } else if (player.state == 2) {
            if (player.delayBeforeNextFrame < 1) {
                player.frameNumber += 1
                if (player.frameNumber > 4) {
                    player.frameNumber = 0
                    player.state = 1
                }
            } else {
                player.delayBeforeNextFrame -= 1
            }
        } else if (player.state == 0) {
            player.delayBeforeNextFrame += 1
            if (player.delayBeforeNextFrame > 3) {
                player.delayBeforeNextFrame = 0
                val futureFrameNumber = player.frameNumber + player.normalStatusArmSwingDirection
                if (futureFrameNumber < 0 || futureFrameNumber > 4) {
                    player.normalStatusArmSwingDirection = -player.normalStatusArmSwingDirection
                }
                player.frameNumber = player.frameNumber + player.normalStatusArmSwingDirection
            }
        }

        if (player.gameEnded) {
            if (player.state == 0) {
                if (player.isWinner) {
                    player.state = 5
                    player.sound.pipikachu = true
                } else {
                    player.state = 6
                }
                player.delayBeforeNextFrame = 0
                player.frameNumber = 0
            }
            processGameEndFrameFor(player)
        }
    }

    /**
     * FUN_00402360 — 컴퓨터(FSM) 의 입력 결정.
     *
     * ⚠️ **이 함수는 학습 대상이 아니라 평가 기준이다.** 원본과 다르면
     *    Track A 의 승률도 Track B 의 held-out 평가도 전부 무의미해진다 (plan.md §8).
     *
     * ⚠️ RNG 소비 타이밍이 사양의 일부다. 아래 `rand() % 20` 은 **`else if` 안에 있어서
     *    앞 조건(착지점이 멀다) 이 참이면 소비되지 않는다.** 두 `if` 로 풀어 쓰면
     *    난수 스트림이 어긋나 그 시점부터 모든 것이 갈라진다.
     */
    private fun letComputerDecideUserInput(
        player: Player,
        ball: Ball,
        theOtherPlayer: Player,
        userInput: PikaUserInput,
    ) {
        userInput.xDirection = 0
        userInput.yDirection = 0
        userInput.powerHit = 0

        var virtualExpectedLandingPointX = ball.expectedLandingPointX
        if (abs(ball.x - player.x) > 100 && abs(ball.xVelocity) < player.computerBoldness + 5) {
            // 공이 멀고 느리다 = 당분간 내 쪽으로 안 온다. 대기 위치로 간다.
            val leftBoundary = player.isPlayer2.toInt() * GROUND_HALF_WIDTH
            if ((ball.expectedLandingPointX <= leftBoundary ||
                    ball.expectedLandingPointX >= player.isPlayer2.toInt() * GROUND_WIDTH + GROUND_HALF_WIDTH) &&
                player.computerWhereToStandBy == 0
            ) {
                // 자기 진영의 중간 지점을 대기 위치로 잡는다.
                virtualExpectedLandingPointX = leftBoundary + (GROUND_HALF_WIDTH / 2)
            }
        }

        if (abs(virtualExpectedLandingPointX - player.x) > player.computerBoldness + 8) {
            userInput.xDirection = if (player.x < virtualExpectedLandingPointX) 1 else -1
        } else if (rand.next() % 20 == 0) {
            // ⚠️ RNG 소비 지점. 위 분기를 타면 소비되지 않는다.
            player.computerWhereToStandBy = rand.next() % 2
        }

        if (player.state == 0) {
            // 서 있을 때 — 점프할지, 다이빙할지
            if (abs(ball.xVelocity) < player.computerBoldness + 3 &&
                abs(ball.x - player.x) < PLAYER_HALF_LENGTH &&
                ball.y > -36 &&
                ball.y < 10 * player.computerBoldness + 84 &&
                ball.yVelocity > 0
            ) {
                userInput.yDirection = -1
            }

            val leftBoundary = player.isPlayer2.toInt() * GROUND_HALF_WIDTH
            val rightBoundary = (player.isPlayer2.toInt() + 1) * GROUND_HALF_WIDTH
            if (ball.expectedLandingPointX > leftBoundary &&
                ball.expectedLandingPointX < rightBoundary &&
                abs(ball.x - player.x) > player.computerBoldness * 5 + PLAYER_LENGTH &&
                ball.x > leftBoundary &&
                ball.x < rightBoundary &&
                ball.y > 174
            ) {
                // 다이빙한다
                userInput.powerHit = 1
                userInput.xDirection = if (player.x < ball.x) 1 else -1
            }
        } else if (player.state == 1 || player.state == 2) {
            // 공중에 있을 때 — 공을 쫓고, 가까우면 파워히트를 검토한다
            if (abs(ball.x - player.x) > 8) {
                userInput.xDirection = if (player.x < ball.x) 1 else -1
            }
            if (abs(ball.x - player.x) < 48 && abs(ball.y - player.y) < 48) {
                val willInputPowerHit = decideWhetherInputPowerHit(player, ball, theOtherPlayer, userInput)
                if (willInputPowerHit) {
                    userInput.powerHit = 1
                    if (abs(theOtherPlayer.x - player.x) < 80 && userInput.yDirection != -1) {
                        userInput.yDirection = -1
                    }
                }
            }
        }
    }

    /**
     * FUN_00402630 — 파워히트를 할지, 한다면 어느 방향으로 할지 결정한다.
     *
     * 이름과 달리 [userInput] 의 x·y 방향까지 세팅한다 (원본 그대로).
     *
     * ⚠️ 진입하자마자 `rand() % 2` 를 **항상** 소비한다. 그 결과로 y 방향 탐색 순서만 바뀐다
     *    (위→아래 / 아래→위). x 방향은 두 경우 모두 1 → 0 순이다.
     *
     * @return 파워히트를 할 것인가?
     */
    private fun decideWhetherInputPowerHit(
        player: Player,
        ball: Ball,
        theOtherPlayer: Player,
        userInput: PikaUserInput,
    ): Boolean {
        // ⚠️ RNG 소비 지점. 조건과 무관하게 매번 한 번.
        val yDirections = if (rand.next() % 2 == 0) intArrayOf(-1, 0, 1) else intArrayOf(1, 0, -1)
        for (xDirection in 1 downTo 0) {
            for (yDirection in yDirections) {
                val expectedLandingPointX = expectedLandingPointXWhenPowerHit(xDirection, yDirection, ball)
                // 상대 진영에 떨어지고, 상대 플레이어에게서 충분히 먼 방향을 고른다.
                if ((expectedLandingPointX <= player.isPlayer2.toInt() * GROUND_HALF_WIDTH ||
                        expectedLandingPointX >= player.isPlayer2.toInt() * GROUND_WIDTH + GROUND_HALF_WIDTH) &&
                    abs(expectedLandingPointX - theOtherPlayer.x) > PLAYER_LENGTH
                ) {
                    userInput.xDirection = xDirection
                    userInput.yDirection = yDirection
                    return true
                }
            }
        }
        return false
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 순수 함수 (RNG 를 쓰지 않는다)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * FUN_00403070 — 공과 플레이어가 충돌했는가?
 *
 * 원형이 아니라 **정사각형 판정**이다. 공 반지름은 쓰지 않는다.
 */
internal fun isCollisionBetweenBallAndPlayerHappened(ball: Ball, playerX: Int, playerY: Int): Boolean {
    var diff = ball.x - playerX
    if (abs(diff) <= PLAYER_HALF_LENGTH) {
        diff = ball.y - playerY
        if (abs(diff) <= PLAYER_HALF_LENGTH) {
            return true
        }
    }
    return false
}

/**
 * FUN_00402dc0 — 공과 세계(벽·네트·바닥) 의 충돌 처리 및 공 위치 세팅.
 *
 * @return 공이 땅에 닿았는가?
 */
internal fun processCollisionBetweenBallAndWorldAndSetBallPosition(ball: Ball): Boolean {
    // 원본 어셈블리에서는 다른 함수(FUN_00402ee0) 의 일이지만 여기서 처리하는 게 맞다.
    ball.previousPreviousX = ball.previousX
    ball.previousPreviousY = ball.previousY
    ball.previousX = ball.x
    ball.previousY = ball.y

    var futureFineRotation = ball.fineRotation + (ball.xVelocity / 2)
    // futureFineRotation 이 정확히 50 이면 아래 두 분기를 모두 비켜간다.
    // 그러면 fineRotation = 50 → rotation = 5 가 되어 **hyper ball 글리치**가 난다.
    // 라운드 끝에 이렇게 되면 xVelocity 가 0 으로 초기화되므로 충돌 전까지 계속 5 에 머문다.
    if (futureFineRotation < 0) {
        futureFineRotation += 50
    } else if (futureFineRotation > 50) {
        futureFineRotation += -50
    }
    ball.fineRotation = futureFineRotation
    ball.rotation = ball.fineRotation / 10

    val futureBallX = ball.x + ball.xVelocity
    // 좌우 경계에서 튕긴다.
    // ⚠️ 좌우가 비대칭이다 (`< BALL_RADIUS` vs `> GROUND_WIDTH`). 원작의 버그로 보이지만
    //    대칭으로 고치면 expectedLandingPointXWhenPowerHit 의 루프가 끝나지 않는 사례가 관측된다.
    //    **원본을 그대로 옮긴다.**
    if (futureBallX < BALL_RADIUS || futureBallX > GROUND_WIDTH) {
        ball.xVelocity = -ball.xVelocity
    }

    var futureBallY = ball.y + ball.yVelocity
    // 위쪽 경계
    if (futureBallY < 0) {
        ball.yVelocity = 1
    }

    // 네트에 닿았는가
    if (abs(ball.x - GROUND_HALF_WIDTH) < NET_PILLAR_HALF_WIDTH && ball.y > NET_PILLAR_TOP_TOP_Y_COORD) {
        if (ball.y <= NET_PILLAR_TOP_BOTTOM_Y_COORD) {
            // 네트 기둥 윗면 — 위로 튕긴다
            if (ball.yVelocity > 0) {
                ball.yVelocity = -ball.yVelocity
            }
        } else {
            // 네트 기둥 옆면 — 바깥쪽으로 밀린다
            if (ball.x < GROUND_HALF_WIDTH) {
                ball.xVelocity = -abs(ball.xVelocity)
            } else {
                ball.xVelocity = abs(ball.xVelocity)
            }
        }
    }

    // ⚠️ 네트 처리가 yVelocity 를 바꿨을 수 있으므로 **다시 계산**한다.
    futureBallY = ball.y + ball.yVelocity
    // 바닥에 닿는가
    if (futureBallY > BALL_TOUCHING_GROUND_Y_COORD) {
        ball.sound.ballTouchesGround = true

        ball.yVelocity = -ball.yVelocity
        ball.punchEffectX = ball.x
        ball.y = BALL_TOUCHING_GROUND_Y_COORD
        ball.punchEffectRadius = BALL_RADIUS
        ball.punchEffectY = BALL_TOUCHING_GROUND_Y_COORD + BALL_RADIUS
        return true
    }
    ball.y = futureBallY
    ball.x = ball.x + ball.xVelocity
    ball.yVelocity += 1

    return false
}

/**
 * FUN_004025e0 — 경기 종료 모션(승자/패자) 프레임 진행.
 */
internal fun processGameEndFrameFor(player: Player) {
    if (player.gameEnded && player.frameNumber < 4) {
        player.delayBeforeNextFrame += 1
        if (player.delayBeforeNextFrame > 4) {
            player.delayBeforeNextFrame = 0
            player.frameNumber += 1
        }
    }
}

/**
 * FUN_004031b0 — 공의 예상 착지점 x 좌표를 계산한다.
 *
 * 공 복사본을 바닥에 닿을 때까지 굴려 본다. [INFINITE_LOOP_LIMIT] 은 원작에 없는 안전장치이지만,
 * **도달 시 동작까지 JS 와 일치해야 하므로 그대로 옮긴다** (tasks.md P7 표적 케이스).
 */
internal fun calculateExpectedLandingPointXFor(ball: Ball) {
    var x = ball.x
    var y = ball.y
    var xVelocity = ball.xVelocity
    var yVelocity = ball.yVelocity

    var loopCounter = 0
    while (true) {
        loopCounter++

        val futureCopyBallX = xVelocity + x
        if (futureCopyBallX < BALL_RADIUS || futureCopyBallX > GROUND_WIDTH) {
            xVelocity = -xVelocity
        }
        if (y + yVelocity < 0) {
            yVelocity = 1
        }

        if (abs(x - GROUND_HALF_WIDTH) < NET_PILLAR_HALF_WIDTH && y > NET_PILLAR_TOP_TOP_Y_COORD) {
            // ⚠️ 여기는 `<` 인데 FUN_00402dc0 은 `<=` 다. 원작자의 실수로 보이지만 그대로 옮긴다.
            if (y < NET_PILLAR_TOP_BOTTOM_Y_COORD) {
                if (yVelocity > 0) {
                    yVelocity = -yVelocity
                }
            } else {
                if (x < GROUND_HALF_WIDTH) {
                    xVelocity = -abs(xVelocity)
                } else {
                    xVelocity = abs(xVelocity)
                }
            }
        }

        y += yVelocity
        if (y > BALL_TOUCHING_GROUND_Y_COORD || loopCounter >= INFINITE_LOOP_LIMIT) {
            break
        }
        x += xVelocity
        yVelocity += 1
    }
    ball.expectedLandingPointX = x
}

/**
 * FUN_00402870 — 파워히트했을 때의 예상 착지점 x 좌표.
 *
 * [calculateExpectedLandingPointXFor] 와 거의 같지만 **네트 처리가 다르다.**
 * 여기서는 기둥 옆면 반사를 계산하지 않고 무조건 위로 튕긴 것으로 친다.
 * 업스트림 주석에 따르면 컴퓨터가 실수하도록 의도된 코드다 — 네트에 맞고 돌아올 공을
 * 종종 파워히트하는 이유가 이것이다. **고치면 FSM 이 원본보다 강해진다.**
 */
internal fun expectedLandingPointXWhenPowerHit(
    userInputXDirection: Int,
    userInputYDirection: Int,
    ball: Ball,
): Int {
    var x = ball.x
    var y = ball.y
    var xVelocity: Int
    var yVelocity = ball.yVelocity

    if (x < GROUND_HALF_WIDTH) {
        xVelocity = (abs(userInputXDirection) + 1) * 10
    } else {
        xVelocity = -(abs(userInputXDirection) + 1) * 10
    }
    yVelocity = abs(yVelocity) * userInputYDirection * 2

    var loopCounter = 0
    while (true) {
        loopCounter++

        val futureCopyBallX = x + xVelocity
        if (futureCopyBallX < BALL_RADIUS || futureCopyBallX > GROUND_WIDTH) {
            xVelocity = -xVelocity
        }
        if (y + yVelocity < 0) {
            yVelocity = 1
        }
        if (abs(x - GROUND_HALF_WIDTH) < NET_PILLAR_HALF_WIDTH && y > NET_PILLAR_TOP_TOP_Y_COORD) {
            // ⚠️ 위 주석 참고. 기둥 옆면 반사가 빠져 있다. 원본 그대로 옮긴다.
            if (yVelocity > 0) {
                yVelocity = -yVelocity
            }
        }
        y += yVelocity
        if (y > BALL_TOUCHING_GROUND_Y_COORD || loopCounter >= INFINITE_LOOP_LIMIT) {
            return x
        }
        x += xVelocity
        yVelocity += 1
    }
}

/** JS 의 `Number(bool)` — FSM 이 진영 경계를 계산할 때 쓴다. */
private fun Boolean.toInt(): Int = if (this) 1 else 0
