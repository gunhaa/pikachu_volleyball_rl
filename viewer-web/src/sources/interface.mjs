/**
 * 입력원 인터페이스. (FR-16, plan.md §5.1.1)
 *
 * Kotlin `Slot` + `Controller.decide(game, isPlayer2, out)` (`Slot.kt`) 과 **같은 모양**이다.
 * 한 개념을 두 언어가 공유해야 Phase 5 에서 "Kotlin 평가에서 정책이 입력을 넣는 자리 =
 * 브라우저에서 넣는 자리" 를 코드만 읽고 확인할 수 있다.
 *
 * @interface InputSource
 *   kind: 'fsm' | 'external'
 *   decide(runner, isPlayer2, out)  external 만. runEngineForNextFrame **직전**에 호출
 *   prepare(runner, isPlayer2)      선택 · 비동기. beginFrame() 뒤 · step() 전 (live-loop.mjs). 있으면 동기 step() 루프로 못 돈다
 *   onRallyStart(runner)            선택. 랠리 시작마다 (엣지 트리거 리셋 등)
 *   reset(runner)                   선택. 러너 reset() 마다
 *
 * | 구현 | kind | decide |
 * |---|---|---|
 * | ReplaySource   | external | 기록된 바이트 → decodeAction |
 * | FsmSource      | fsm      | **호출되지 않는다** — 러너가 PikaPhysics(isComputer) 로 반영, 엔진이 입력을 덮어쓴다 |
 * | KeyboardSource | external | PikaKeyboard.getInput() 후 값 복사 (P7) |
 * | ScriptedSource | external | 결정론 함수 (테스트, M4-j) |
 * | PolicySource   | external | prepare 에서 관측 → ONNX argmax, decide 에서 decodePolicyAction + EdgeTrigger (Phase 5) |
 */
'use strict';

export const KIND = Object.freeze({ FSM: 'fsm', EXTERNAL: 'external' });
