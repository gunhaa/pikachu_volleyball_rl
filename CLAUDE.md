# CLAUDE.md

## 프로젝트

RL 학습용 프로젝트.

**최종 목표**

1. RL binary 로 기존 FSM(컴퓨터)을 이긴다.
2. zero 학습으로 AI 끼리 대전시킨다.
3. 그 결과로 다시 FSM(컴퓨터)과 대전한다.

> FSM(컴퓨터) = 업스트림 `physics.js` 의 내장 컴퓨터 AI (`letComputerDecideUserInput`).

## 문서 맵

| 문서 | 역할 | 수명 |
|---|---|---|
| `ROADMAP.md` | 프로젝트 전체 로드맵 (Phase 0~7) | 영구 |
| `plan.md` | 진행 중인 작업의 계획 | 작업 단위 |
| `PRD.md` | 진행 중인 작업의 요구사항 정의 | 작업 단위 |
| `tasks.md` | 진행 중인 작업의 구현 태스크 | 작업 단위 |
| `history/` | 완료된 작업의 `plan.md` · `PRD.md` · `tasks.md` 보관 | 영구 |

## 작업 규칙

1. 작업을 진행할 때 `plan.md` · `PRD.md` · `tasks.md` 를 작성한다.
2. `tasks.md` 는 **관심사 단위로 Phase 를 나누어** 구성한다.
3. 작업이 완료되면 세 문서를 함께 `history/<YYYY-MM-DD>-<작업명>/` 으로 이관한다.
