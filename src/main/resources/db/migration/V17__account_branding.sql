-- V17 — 브랜딩 페이지(강사) / 내 프로필(일반) 스키마.
--
-- 번호가 V14 가 아닌 이유: 이 작업을 시작한 시점의 master 기준으로는 V14 가 다음 번호였지만, 그 사이
-- V14(payment_order_refunded_amount, #201)·V15(refund_order_attempt_log, #202)가 머지됐고
-- V16(slot_change_diff_payment)은 아직 열린 PR #204 가 선점하고 있다. 병렬 작업 중엔 "머지된 것"만이
-- 아니라 "열린 PR 이 잡아둔 번호"까지 피해야 한다 — 같은 번호가 둘 생기면 Flyway 가 부팅을 막는다
-- (2026-06-28 중복 V6 사고와 같은 유형).
--
-- 계정당 1개(account_branding) + 공식기록(branding_record) + 게시물(branding_post → media/tag).
-- 강사 전용이 아니라 모든 계정이 가진다(사용자 결정 D2) — 그래서 instructor_ 가 아니라 account_ 접두사.
--
-- 이 마이그레이션에 게시물 3개 테이블도 함께 만든다. 엔티티/엔드포인트는 후속 PR 에서 붙지만,
-- hbm2ddl=validate 는 '엔티티에 대응하는 테이블'만 보므로 테이블이 먼저 있어도 무해하고,
-- 마이그레이션을 한 번으로 끝내는 편이 배포 횟수와 사고 표면을 줄인다.
--
-- ⚠️ account.nick_name UNIQUE 인덱스는 이 마이그레이션에 없다 — 별도 PR 로 분리했다.
--    닉네임 중복 dedupe 는 '유저에게 보이는 식별자'를 바꾸는 동작이라 실데이터 사전 점검과 승인이
--    선행돼야 하고, 그 조회 경로가 현재 인프라에 없다(ECS Exec 비활성 + 런타임 이미지에 mysql 클라이언트 없음).
--    그때까지 공개 조회는 결정적 정렬(가장 오래된 계정)로 중복에 안전하게 동작한다.
--
-- 멱등: CREATE TABLE IF NOT EXISTS. ECS 가 실패 태스크를 빠르게 재시작해 같은 마이그레이션이
-- 동시/재시도 실행될 수 있다(2026-06-28 prod 사고) — bare CREATE 는 1050 으로 실패하고 그 실패가
-- 기록되면 이후 모든 부팅이 막힌다.

CREATE TABLE IF NOT EXISTS `account_branding` (
  `id`             bigint       NOT NULL AUTO_INCREMENT,
  `account_id`     bigint       NOT NULL,
  `tagline`        varchar(60)  COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `bio`            varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `location_label` varchar(60)  COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `is_published`   bit(1)       NOT NULL DEFAULT b'1',
  `created_at`     datetime     DEFAULT NULL,
  `updated_at`     datetime     DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_account_branding_account` (`account_id`),
  CONSTRAINT `fk_account_branding_account` FOREIGN KEY (`account_id`) REFERENCES `account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 공식 기록. value 는 종목마다 단위가 달라(깊이 -75m / 거리 180m / 시간 6:24) 문자열 원문으로 둔다.
CREATE TABLE IF NOT EXISTS `branding_record` (
  `id`          bigint      NOT NULL AUTO_INCREMENT,
  `branding_id` bigint      NOT NULL,
  `medal`       varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `event_code`  varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  -- `value` 가 아니라 `record_value`: value 는 H2(테스트 DB)의 예약어라 스키마 생성이 깨진다.
  -- API 필드명은 계약대로 value 를 유지한다.
  `record_value` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `sort_order`  int         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `ix_branding_record_order` (`branding_id`, `sort_order`),
  CONSTRAINT `fk_branding_record_branding` FOREIGN KEY (`branding_id`) REFERENCES `account_branding` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 게시물. linked_course_id 는 nullable + ON DELETE SET NULL — 코스가 지워져도 게시물은 남고 연결만 끊긴다.
-- 인덱스는 그리드 조회 그대로(공개 필터 → 고정 우선 → 최신순).
CREATE TABLE IF NOT EXISTS `branding_post` (
  `id`               bigint        NOT NULL AUTO_INCREMENT,
  `branding_id`      bigint        NOT NULL,
  `caption`          varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `location_label`   varchar(60)   COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `pinned`           bit(1)        NOT NULL DEFAULT b'0',
  `is_hidden`        bit(1)        NOT NULL DEFAULT b'0',
  `linked_course_id` bigint        DEFAULT NULL,
  `created_at`       datetime      DEFAULT NULL,
  `updated_at`       datetime      DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_branding_post_grid` (`branding_id`, `is_hidden`, `pinned`, `created_at`),
  KEY `ix_branding_post_course` (`linked_course_id`),
  CONSTRAINT `fk_branding_post_branding` FOREIGN KEY (`branding_id`) REFERENCES `account_branding` (`id`),
  CONSTRAINT `fk_branding_post_course` FOREIGN KEY (`linked_course_id`) REFERENCES `course` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- kind 는 PHOTO|VIDEO. VIDEO 는 스키마 자리만 예약하고 업로드는 거부한다(D1) — 나중에 붙일 때
-- 마이그레이션이 필요 없게.
CREATE TABLE IF NOT EXISTS `branding_post_media` (
  `id`         bigint       NOT NULL AUTO_INCREMENT,
  `post_id`    bigint       NOT NULL,
  `kind`       varchar(16)  COLLATE utf8mb4_unicode_ci NOT NULL,
  `url`        varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `sort_order` int          NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `ix_branding_post_media_order` (`post_id`, `sort_order`),
  CONSTRAINT `fk_branding_post_media_post` FOREIGN KEY (`post_id`) REFERENCES `branding_post` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `branding_post_tag` (
  `id`      bigint      NOT NULL AUTO_INCREMENT,
  `post_id` bigint      NOT NULL,
  `tag`     varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_branding_post_tag_post` (`post_id`),
  CONSTRAINT `fk_branding_post_tag_post` FOREIGN KEY (`post_id`) REFERENCES `branding_post` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
