-- V28 — 세션 단체 채팅(방·참여자·메시지·읽음상태) 스키마.
--
-- 계약: scratchpad/CONTRACT.md (v3), 결정: 같은 폴더 DECISIONS.md
--
-- ## 핵심 결정 1 — 방의 PK 는 세션 id 이고, availability_session 으로의 FK 를 걸지 않는다
--
-- 채팅 그룹의 단위는 "강사 가용시간 슬롯"(= availability_session) 이다(D1). 그래서 방 : 세션 = 1 : 1 이고,
-- 방에 별도 surrogate id 를 두는 대신 세션 id 를 그대로 PK 로 쓴다.
--
-- FK 를 걸지 않는 이유가 핵심이다. `availability.SessionCleaner` 는 **점유(활성 신청 + hold)가 0 이 되면
-- 세션 행을 물리 삭제**한다("session 존재 ⟺ 점유 > 0" 불변식). 결제한 수강생이 전원 취소·환불하면 실제로
-- 일어난다. 여기에 FK 가 걸려 있으면 그 삭제가 제약 위반으로 실패해 **환불 플로우가 깨진다**. FK 를 없애면
-- SessionCleaner 를 건드릴 필요가 없고(무변경), 세션이 사라져도 방과 메시지가 CS·감사용으로 남는다.
-- 세션이 사라진 방은 읽기 전용(CLOSED)으로 파생된다 — 아래 결정 2.
--
-- FK 없는 참조는 이 레포에 선례가 있다: `user_notification.recipient_account_id`
-- ("FK 제약 없음 — outbox 와 같은 기조").
--
-- **안전성 근거(중요)**: 세션 id 가 재사용되면 옛 방과 새 방이 PK 충돌한다. prod RDS 는
-- `engine_version = "8.4"`, 로컬 docker 도 `mysql:8.4` 다. MySQL 8.0+ 는 InnoDB AUTO_INCREMENT 카운터를
-- redo log 에 영속하므로 5.7 처럼 재시작 시 MAX(id)+1 로 되감기지 않고, 삭제된 id 도 재사용하지 않는다.
-- 따라서 한 번 부여된 세션 id 는 다른 세션에 다시 붙지 않는다 — 같은 슬롯이 지워졌다 다시 생기면
-- 새 id → 새 방이고, 옛 CLOSED 방과 충돌하지 않는다.
--
-- ## 핵심 결정 2 — 방 상태(ACTIVE/CLOSED)를 저장하지 않는다
--
-- 상태는 읽을 때 파생한다: 세션 생존 여부(`availability_session` 존재) + `closes_at` 경과.
-- 저장하면 "세션이 지워졌는데 방은 아직 ACTIVE" 같은 어긋남을 배치로 따라다녀야 한다. 이 레포가
-- `SlotStatus` 를 저장하지 않고 AvailabilityService 가 파생하는 것과 같은 기조다.
--
-- `closes_at` 만 저장한다 — 세션 종료(civil date+end_time)를 KST 로 해석한 instant + 24h. civil→instant
-- 변환에 존이 필요한데 venue.timeZone 이 아직 없어 KST 고정이다(payment.RefundService 와 같은 선례).
--
-- ## 핵심 결정 3 — 참여자를 행으로 실체화한다 (파생 계산 X)
--
-- 참여자 = 강사 + 그 세션에 OCCUPYING(ACCEPT_PENDING/CONFIRMED = 결제완료) 회차를 가진 수강생이다.
-- 매번 enrollment 에서 계산할 수도 있지만, **세션이 삭제된 뒤에는 그 계산이 불가능**해져 CLOSED 방의
-- 권한 판정 자체가 안 된다. 실체화하면 권한·푸시 fan-out·읽음상태가 전부 이 테이블 하나로 닫힌다.
-- 이탈은 행 삭제가 아니라 `left_at` 이다 — 과거 메시지의 발신자 이름을 계속 해석해야 하기 때문.
--
-- ## 멱등성
--
-- ECS 가 실패 태스크를 빠르게 재시작해 같은 마이그레이션이 동시/재시도 실행될 수 있고(2026-06-28 prod
-- 사고 #121), 한 번 실패로 기록되면 이후 모든 부팅이 막힌다. 신규 테이블뿐이라 CREATE TABLE IF NOT EXISTS
-- 로 충분하다(ADD COLUMN/INDEX 가 없어 V2 의 information_schema 프로시저는 필요 없다).

-- ────────────────────────────────────────────────────────────────
-- 1. 방
-- ────────────────────────────────────────────────────────────────
-- id = availability_session.id (assigned). FK 없음 — 파일 상단 "핵심 결정 1" 참고.
-- 헤더 표시에 필요한 슬롯 정보는 스냅샷으로 박는다(course_title/round_index/venue_name/date/시간).
-- 세션이 사라져도 헤더가 깨지지 않아야 하고, enrollment_round 가 같은 이유로 슬롯 스냅샷을 들고 있다.
CREATE TABLE IF NOT EXISTS `chat_room` (
  `id`            bigint      NOT NULL,
  `instructor_id` bigint      NOT NULL,
  `course_title`  varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `round_index`   int         DEFAULT NULL,
  `venue_name`    varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `date`          date        DEFAULT NULL,
  `start_time`    time        DEFAULT NULL,
  `end_time`      time        DEFAULT NULL,
  `closes_at`     datetime    NOT NULL,
  `created_at`    datetime    DEFAULT NULL,
  `updated_at`    datetime    DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ────────────────────────────────────────────────────────────────
-- 2. 참여자
-- ────────────────────────────────────────────────────────────────
-- account_id 는 FK 를 걸지 않는다(user_notification 과 같은 기조 — 채팅이 account 에 강결합되지 않게).
-- room_id 는 chat 도메인 내부라 FK + CASCADE 를 건다.
CREATE TABLE IF NOT EXISTS `chat_participant` (
  `id`         bigint      NOT NULL AUTO_INCREMENT,
  `room_id`    bigint      NOT NULL,
  `account_id` bigint      NOT NULL,
  `role`       varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `joined_at`  datetime    DEFAULT NULL,
  -- 이탈 시각. NULL = 현재 참여자. 행을 지우지 않는 이유는 상단 "핵심 결정 3" 참고.
  `left_at`    datetime    DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat_participant` (`room_id`, `account_id`),
  KEY `ix_chat_participant_account` (`account_id`),
  CONSTRAINT `fk_chat_participant_room` FOREIGN KEY (`room_id`)
    REFERENCES `chat_room` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ────────────────────────────────────────────────────────────────
-- 3. 메시지
-- ────────────────────────────────────────────────────────────────
-- id 가 곧 커서다(단조증가 AUTO_INCREMENT). 커서 페이지네이션을 쓰는 이유는 채팅이 append-heavy 라
-- offset 페이지가 새 메시지 유입에 밀려 과거 스크롤에서 중복·누락이 나기 때문(계약 §9-2).
--
-- client_message_id = 전송 멱등키. 응답이 유실된 뒤 사용자가 재전송하면 중복 메시지가 남는데,
-- FE 완화("자동 재시도 금지")로는 **수동 재전송**을 막을 수 없다. 후행 도입은 스키마 변경 + 백필이라
-- 처음부터 넣는다. UNIQUE (sender_account_id, client_message_id) — MySQL 은 NULL 중복을 허용하므로
-- sender 가 NULL 인 SYSTEM 메시지 다건과 공존한다.
CREATE TABLE IF NOT EXISTS `chat_message` (
  `id`                bigint       NOT NULL AUTO_INCREMENT,
  `room_id`           bigint       NOT NULL,
  -- SYSTEM 메시지는 NULL. FK 없음(account 강결합 회피).
  `sender_account_id` bigint       DEFAULT NULL,
  `client_message_id` varchar(64)  COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `kind`              varchar(16)  COLLATE utf8mb4_unicode_ci NOT NULL,
  `text`              varchar(1000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `deleted`           bit(1)       NOT NULL DEFAULT b'0',
  `created_at`        datetime     DEFAULT NULL,
  PRIMARY KEY (`id`),
  -- 커서 조회: WHERE room_id = ? AND id > ? / id < ?
  KEY `ix_chat_message_room_id` (`room_id`, `id`),
  -- 레이트리밋 count: WHERE sender_account_id = ? AND created_at > ?
  KEY `ix_chat_message_sender_created` (`sender_account_id`, `created_at`),
  UNIQUE KEY `uk_chat_message_client` (`sender_account_id`, `client_message_id`),
  CONSTRAINT `fk_chat_message_room` FOREIGN KEY (`room_id`)
    REFERENCES `chat_room` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ────────────────────────────────────────────────────────────────
-- 4. 읽음 상태
-- ────────────────────────────────────────────────────────────────
-- unread = 이 값보다 큰 id 중 내가 보내지 않은 메시지 수. 행이 없으면 0 으로 간주(LEFT JOIN).
CREATE TABLE IF NOT EXISTS `chat_read_state` (
  `id`                   bigint   NOT NULL AUTO_INCREMENT,
  `room_id`              bigint   NOT NULL,
  `account_id`           bigint   NOT NULL,
  `last_read_message_id` bigint   NOT NULL DEFAULT 0,
  `updated_at`           datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat_read_state` (`room_id`, `account_id`),
  CONSTRAINT `fk_chat_read_state_room` FOREIGN KEY (`room_id`)
    REFERENCES `chat_room` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
