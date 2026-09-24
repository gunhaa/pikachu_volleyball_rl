-- 분석 DB 스키마 v1 (Phase 4, plan.md §6.2)
--
-- 이 DB 는 **캐시**다. 진실 공급원은 리플레이(*.pkr)이고, rally · power_hit 는 전부 리플레이를
-- 재생해서 파생한다 (`analysis rebuild-stats`). 그래서 마이그레이션 도구가 없다 — 스키마를 바꾸면
-- SCHEMA_VERSION 을 올리고, 볼륨을 지우고, 다시 적재한다 (plan.md §6.3).
--
-- ⚠️ `analysis` 는 시작할 때 schema_version 을 대조한다 (pika.analysis.Db.SCHEMA_VERSION).
-- ⚠️ 문장 구분은 `;` + 줄바꿈이다. DB 테스트가 이 파일을 JDBC 로 그대로 실행한다 — DELIMITER 를 쓰지 않는다.

CREATE TABLE schema_version (
  version INT NOT NULL PRIMARY KEY
);
INSERT INTO schema_version VALUES (1);

-- 경기 묶음. baseline = 기준선, eval = 평가, selfplay = Phase 6~7, live = 브라우저 라이브 대전 (P7).
CREATE TABLE match_set (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  name       VARCHAR(128) NOT NULL UNIQUE,
  kind       ENUM('baseline', 'eval', 'selfplay', 'live') NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  note       TEXT NULL
);

-- 누가 뛰었는가. 재생에 필요한 정보(FSM 인가)는 리플레이 헤더에 이미 있고, 여기는 **누구**인지를 담는다.
-- identity 는 유일 키다 — `kind:sha256` (체크포인트) 또는 `kind:label:<label>` 또는 `fsm:`.
-- (UNIQUE(kind, checkpoint_sha256) 는 MySQL 에서 NULL 끼리 겹쳐도 통과하므로 쓰지 않는다.)
CREATE TABLE participant (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  identity          VARCHAR(200) NOT NULL UNIQUE,
  kind              ENUM('fsm', 'external', 'human') NOT NULL,
  checkpoint_sha256 CHAR(64) NULL,
  label             VARCHAR(128) NULL
);

CREATE TABLE game (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  set_id            BIGINT NOT NULL,
  p1_id             BIGINT NOT NULL,
  p2_id             BIGINT NOT NULL,
  replay_sha256     CHAR(64) NOT NULL UNIQUE,
  replay            MEDIUMBLOB NOT NULL,
  seed_mode         TINYINT NOT NULL,
  winning_score     TINYINT UNSIGNED NOT NULL,
  max_rally_frames  INT NOT NULL,
  fixed_boldness_p1 TINYINT NOT NULL,
  fixed_boldness_p2 TINYINT NOT NULL,
  first_serve_p2    BOOLEAN NOT NULL,
  ended             BOOLEAN NOT NULL,
  score_p1          TINYINT UNSIGNED NOT NULL,
  score_p2          TINYINT UNSIGNED NOT NULL,
  winner            TINYINT NULL,
  frames            INT NOT NULL,
  rallies           INT NOT NULL,
  truncated         INT NOT NULL,
  chain_sha256      CHAR(64) NOT NULL,   -- Kotlin 재생 체인. 뷰어가 JS 체인과 대조한다
  env_index         INT NULL,
  game_in_env       INT NULL,
  FOREIGN KEY (set_id) REFERENCES match_set (id),
  FOREIGN KEY (p1_id) REFERENCES participant (id),
  FOREIGN KEY (p2_id) REFERENCES participant (id),
  INDEX game_set (set_id)
);

-- 리플레이에서 파생 (P5). outcome: 0/1 득점자, -1 truncated, -2 미완.
CREATE TABLE rally (
  game_id       BIGINT NOT NULL,
  idx           INT NOT NULL,
  seed          INT NULL,               -- RALLY 규약만
  server_p2     BOOLEAN NOT NULL,
  outcome       TINYINT NOT NULL,
  frames        INT NOT NULL,
  landing_x     INT NULL,               -- 득점 랠리의 ball.punchEffectX
  touches_p1    INT NOT NULL,
  touches_p2    INT NOT NULL,
  power_hits_p1 INT NOT NULL,
  power_hits_p2 INT NOT NULL,
  PRIMARY KEY (game_id, idx),
  FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE CASCADE
);

-- 파워히트 한 번 = 한 행. 성공률의 분모 · 분자를 집계가 아니라 행으로 남긴다 (P5).
CREATE TABLE power_hit (
  game_id   BIGINT NOT NULL,
  rally_idx INT NOT NULL,
  frame     INT NOT NULL,               -- 랠리 안의 프레임 (1부터)
  hitter    TINYINT NOT NULL,
  success   BOOLEAN NOT NULL,
  PRIMARY KEY (game_id, rally_idx, frame, hitter),
  FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE CASCADE
);
