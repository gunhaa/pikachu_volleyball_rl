-- 테스트 전용 DB. analysis 의 DB 테스트가 여기서 스키마를 새로 깔고 지운다 — 본 DB(pika)를 건드리지 않는다.
CREATE DATABASE IF NOT EXISTS pika_test CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
GRANT ALL PRIVILEGES ON pika_test.* TO 'pika'@'%';
FLUSH PRIVILEGES;
