-- 인앱 알림함(user_notification) — outbox(전송 시도 원장)와 분리된 도메인 사실 원장.
--
-- 왜 겸용하지 않는가: outbox 는 SENT 를 30일 뒤 지우고, 수신자에게 디바이스 토큰이 없으면
-- GAVE_UP 이 된다. 웹 사용자·앱 미설치 사용자가 정확히 그 경우인데 그들이야말로 알림함이
-- 가장 필요한 대상이라, 겸용하면 durability 라는 목적 자체가 무너진다.
--
-- 멱등 필수: ECS 가 실패 task 를 빠르게 재시작해 같은 마이그레이션이 동시/재시도 실행될 수 있고,
-- 그때 bare CREATE TABLE 은 1050(table already exists)로 '실패 기록' 되어 이후 모든 부팅을 막는다(#121).

CREATE TABLE IF NOT EXISTS user_notification (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    -- ⚠️ VARCHAR(36) 이어야 한다. CHAR(36) 로 두면 Flyway 는 통과하지만 hbm2ddl=validate 가
    -- "found [char], but expecting [varchar(36)]" 로 부팅을 거부한다(엔티티가 String + length=36).
    -- 테스트는 H2 + Flyway OFF 라 엔티티에서 스키마를 만들므로 이 불일치를 못 잡는다 — 실제로 밟았다.
    notification_id      VARCHAR(36)  NOT NULL,
    recipient_account_id BIGINT       NOT NULL,
    type                 VARCHAR(32)  NOT NULL,
    title                VARCHAR(255) NOT NULL,
    body                 VARCHAR(500) NOT NULL,
    data                 TEXT         NULL,
    read_at              DATETIME     NULL,
    created_at           DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_notification_notification_id (notification_id),
    KEY idx_user_notif_recipient_created (recipient_account_id, created_at),
    KEY idx_user_notif_recipient_unread (recipient_account_id, read_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
