-- V19 — 커뮤니티(글·사진·댓글·좋아요·북마크·같이가요·신고) 스키마.
--
-- 계약: scratchpad/community-handoff/CONTRACT.md (v1.0), 결정: 같은 폴더 DECISIONS.md
--
-- ## 핵심 결정 1 — 게시물은 새 테이블을 만들지 않고 `branding_post` 를 확장한다
--
-- 브랜딩 게시물(V17)이 이미 미디어·태그 자식 테이블, 연결 강의, 숨김, 고정, UTC 타임스탬프를 갖고 있다.
-- 커뮤니티 포스트에 없는 건 카테고리·제목·노출 플래그뿐이라 컬럼 4개만 더하면 된다.
--
-- 별도 테이블 + 맵핑으로 가지 않은 이유: `account_branding` 은 계정당 1행(UNIQUE)이고 글의 작성자는
-- 1명이라 "글 ↔ 브랜딩 페이지" 는 항상 0..1 이다. 맵핑 테이블을 두면 모든 글에 정확히 0행 또는 1행이
-- 생긴다 — 조인을 한 번 더 타는 boolean 이다. 게다가 두 테이블로 나누면 브랜딩 그리드가 UNION 이 되어
-- 정렬·페이징·totalElements 가 전부 얹히고, 좋아요·댓글·신고가 폴리모픽 FK 로 빠진다.
--
-- ## 핵심 결정 2 — 테이블 이름을 바꾸지 않는다 (롤링 배포 사고 방지)
--
-- 논리적으로는 community_post 지만 물리 테이블명은 `branding_post` 로 유지한다.
-- ECS 롤링 배포 중에는 구버전 태스크와 신버전 태스크가 동시에 살아 있다. 신버전이 부팅하며 Flyway 로
-- RENAME 을 실행하는 순간, 아직 트래픽을 받는 구버전 태스크가 존재하지 않는 테이블을 조회해
-- **브랜딩 페이지 전체가 500** 이 된다(드레인될 때까지). 이름은 내부 구현이고 API 경로가 계약이므로
-- 바꿔서 얻는 게 없다. 엔티티는 CommunityPost 로 쓰되 @Table(name = "branding_post") 를 명시한다.
-- 이름 정리가 필요하면 트래픽 없는 시점에 별도 마이그레이션으로 분리한다.
--
-- ## 핵심 결정 3 — 노출은 브랜딩 → 커뮤니티 단방향
--
-- 브랜딩은 하이라이트(남기고 싶은 것만), 커뮤니티는 흐름(오늘의 이야기)이다.
--   브랜딩에서 작성  → show_on_profile=1, show_in_feed=1  (피드에도 새 글로 등장)
--   커뮤니티에서 작성 → show_on_profile=0, show_in_feed=1  (브랜딩 그리드엔 안 나옴)
-- 신규 쓰기는 두 플래그를 서비스가 경로별로 명시 설정한다. 아래 DEFAULT 는 **기존 행 backfill 용**이다.
-- 기존 브랜딩 글은 전부 프로필 글이므로 show_on_profile=1, 그리고 커뮤니티 피드에는
-- **소급 노출하지 않는다**(show_in_feed=0) — 유저가 동의한 적 없는 노출이고, 되돌리려면 글마다
-- 숨겨야 한다. 반대 방향(나중에 열어주기)이 훨씬 쉽다.
--
-- ## 참여 신청 테이블은 만들지 않는다
--
-- 같이가요 "참여 신청" 은 별도 기능으로 만들지 않기로 확정됐다(DECISIONS.md §3).
-- 사용자 의도가 "신청류 = 기존 수강신청(예약) 플로우" 이므로, 향후 버디 참여도 커스텀 신청 테이블이
-- 아니라 예약 플로우 통합으로 설계한다. 그래서 community_match_participant 가 여기 없다.
--
-- ## 멱등성
--
-- ECS 가 실패 태스크를 빠르게 재시작해 같은 마이그레이션이 동시/재시도 실행될 수 있고(2026-06-28 prod
-- 사고 #121), 한 번 실패로 기록되면 이후 모든 부팅이 막힌다. CREATE TABLE 은 IF NOT EXISTS 로,
-- MySQL 이 IF NOT EXISTS 를 지원하지 않는 ADD COLUMN/ADD INDEX 는 information_schema + 프로시저로
-- 조건부 처리한다(V2 패턴).

DROP PROCEDURE IF EXISTS pd_add_col;
DROP PROCEDURE IF EXISTS pd_add_index;

DELIMITER //

CREATE PROCEDURE pd_add_col(IN tbl VARCHAR(64), IN col VARCHAR(64), IN ddl TEXT)
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col) THEN
    SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', ddl);
    PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END //

CREATE PROCEDURE pd_add_index(IN tbl VARCHAR(64), IN idx VARCHAR(64), IN cols TEXT)
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND INDEX_NAME = idx) THEN
    SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD INDEX `', idx, '` (', cols, ')');
    PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END //

DELIMITER ;

-- ────────────────────────────────────────────────────────────────
-- 1. branding_post 확장 (= 논리적 community_post)
-- ────────────────────────────────────────────────────────────────

-- category 는 NULL 을 허용한다. 기존 브랜딩 글에는 카테고리 개념이 없었고,
-- 브랜딩 작성 경로에서 카테고리를 받을지는 별도 결정(CONTRACT.md §9-A)이기 때문.
-- NULL = 카테고리 없는 글 → 카테고리 필터에는 안 잡히고 "전체" 피드에만 노출된다.
CALL pd_add_col('branding_post', 'category',
                'varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL');

-- title 도 NULL 허용 — 커뮤니티 작성 경로에서는 앱 레벨 필수지만,
-- 브랜딩 게시물은 caption 이 곧 본문이라 제목이 없다.
CALL pd_add_col('branding_post', 'title',
                'varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL');

-- DEFAULT 값의 의미는 파일 상단 "핵심 결정 3" 참고 — 기존 행 backfill 용이다.
CALL pd_add_col('branding_post', 'show_in_feed',    "bit(1) NOT NULL DEFAULT b'0'");
CALL pd_add_col('branding_post', 'show_on_profile', "bit(1) NOT NULL DEFAULT b'1'");

-- 커뮤니티 피드 조회: show_in_feed=1 AND is_hidden=0 [AND category=?] ORDER BY created_at DESC
CALL pd_add_index('branding_post', 'ix_community_feed',
                  '`show_in_feed`, `is_hidden`, `category`, `created_at`');

-- 브랜딩 그리드용 인덱스는 새로 만들지 않는다. 기존 ix_branding_post_grid
-- (branding_id, is_hidden, pinned, created_at) 가 이미 branding_id 로 좁히므로,
-- 그 위에 show_on_profile 을 필터로 얹는 비용은 무시할 수 있다(계정당 게시물 수는 작다).
-- 거의 같은 인덱스를 하나 더 두면 쓰기 비용만 늘어난다.

-- 인기 태그 집계(GROUP BY tag)용. 기존엔 post_id 인덱스만 있었다.
CALL pd_add_index('branding_post_tag', 'ix_branding_post_tag_tag', '`tag`');

-- ────────────────────────────────────────────────────────────────
-- 2. 같이가요 정형 필드 (1:1 사이드 테이블)
-- ────────────────────────────────────────────────────────────────
-- 4개 카테고리 중 MATCH 에만 있는 필드라 메인 테이블에 nullable 컬럼으로 붙이지 않았다.
-- JSON 컬럼도 기각 — meet_date 로 정렬·마감 판정을 하는데 JSON 은 색인이 안 걸려 풀스캔이 된다
-- (branding_post_tag 를 JSON 이 아니라 자식 행으로 둔 것과 같은 이유).
CREATE TABLE IF NOT EXISTS `community_post_match` (
  `post_id`     bigint      NOT NULL,
  `meet_date`   date        NOT NULL,
  `meet_time`   time        DEFAULT NULL,
  `capacity`    int         NOT NULL,
  `level_label` varchar(60) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`post_id`),
  KEY `ix_community_match_date` (`meet_date`),
  CONSTRAINT `fk_community_match_post` FOREIGN KEY (`post_id`) REFERENCES `branding_post` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ────────────────────────────────────────────────────────────────
-- 3. 좋아요 · 북마크 (마커 행 + UNIQUE = 멱등)
-- ────────────────────────────────────────────────────────────────
-- UNIQUE 가 핵심이다. POST 를 두 번 보내도 좋아요는 1개 — 재시도·연타에 안전하다.
-- 레거시 lecture_mark(강의 찜)에는 UNIQUE 가 없어 같은 유저가 여러 번 찜할 수 있다. 베끼지 않는다.
-- 올바른 선례는 venue_favorite 의 (owner_id, venue_ref_id) UNIQUE.
CREATE TABLE IF NOT EXISTS `community_post_like` (
  `id`         bigint   NOT NULL AUTO_INCREMENT,
  `post_id`    bigint   NOT NULL,
  `account_id` bigint   NOT NULL,
  `created_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_community_post_like` (`post_id`, `account_id`),
  KEY `ix_community_post_like_account` (`account_id`),
  CONSTRAINT `fk_community_post_like_post`    FOREIGN KEY (`post_id`)    REFERENCES `branding_post` (`id`),
  CONSTRAINT `fk_community_post_like_account` FOREIGN KEY (`account_id`) REFERENCES `account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- account_id 인덱스는 "저장한 글" 목록(?bookmarkedByMe=true)이 이 방향으로 조회하기 때문.
CREATE TABLE IF NOT EXISTS `community_post_bookmark` (
  `id`         bigint   NOT NULL AUTO_INCREMENT,
  `post_id`    bigint   NOT NULL,
  `account_id` bigint   NOT NULL,
  `created_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_community_post_bookmark` (`post_id`, `account_id`),
  KEY `ix_community_post_bookmark_account` (`account_id`, `created_at`),
  CONSTRAINT `fk_community_post_bookmark_post`    FOREIGN KEY (`post_id`)    REFERENCES `branding_post` (`id`),
  CONSTRAINT `fk_community_post_bookmark_account` FOREIGN KEY (`account_id`) REFERENCES `account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ────────────────────────────────────────────────────────────────
-- 4. 댓글 (1-depth 대댓글) · 댓글 좋아요
-- ────────────────────────────────────────────────────────────────
-- parent_comment_id 는 최상위 댓글만 가리킬 수 있다(대댓글에 대댓글 금지). DB 로는 표현할 수 없어
-- 서비스에서 강제한다 — 부모의 parent_comment_id 가 NULL 인지 확인한다.
--
-- 댓글은 게시물과 달리 **soft delete** 다. 대댓글이 달린 부모를 물리 삭제하면 스레드가 끊기므로
-- is_deleted 로 남기고 본문을 "삭제된 댓글입니다" 로 대체해 렌더한다.
CREATE TABLE IF NOT EXISTS `community_comment` (
  `id`                bigint        NOT NULL AUTO_INCREMENT,
  `post_id`           bigint        NOT NULL,
  `parent_comment_id` bigint        DEFAULT NULL,
  `account_id`        bigint        NOT NULL,
  `body`              varchar(1000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `is_deleted`        bit(1)        NOT NULL DEFAULT b'0',
  `created_at`        datetime      DEFAULT NULL,
  `updated_at`        datetime      DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_community_comment_thread` (`post_id`, `parent_comment_id`, `created_at`),
  KEY `ix_community_comment_account` (`account_id`),
  CONSTRAINT `fk_community_comment_post`    FOREIGN KEY (`post_id`)           REFERENCES `branding_post` (`id`),
  CONSTRAINT `fk_community_comment_parent`  FOREIGN KEY (`parent_comment_id`) REFERENCES `community_comment` (`id`),
  CONSTRAINT `fk_community_comment_account` FOREIGN KEY (`account_id`)        REFERENCES `account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `community_comment_like` (
  `id`         bigint   NOT NULL AUTO_INCREMENT,
  `comment_id` bigint   NOT NULL,
  `account_id` bigint   NOT NULL,
  `created_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_community_comment_like` (`comment_id`, `account_id`),
  CONSTRAINT `fk_community_comment_like_comment` FOREIGN KEY (`comment_id`) REFERENCES `community_comment` (`id`),
  CONSTRAINT `fk_community_comment_like_account` FOREIGN KEY (`account_id`) REFERENCES `account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ────────────────────────────────────────────────────────────────
-- 5. 신고 (2-A — 접수 + 어드민 수동 처리)
-- ────────────────────────────────────────────────────────────────
-- target_type/target_id 는 의도적으로 FK 가 없다 — 게시물과 댓글 두 종류를 가리키는 폴리모픽 참조라
-- DB 제약을 걸 수 없다. 대상 존재 확인은 접수 시점에 서비스가 한다.
--
-- (target_type, target_id, reporter_account_id) UNIQUE 로 같은 사람의 중복 신고를 막는다.
-- 중복 신고는 에러가 아니라 200 멱등으로 처리한다(이미 신고한 걸 다시 눌러도 사용자 입장에선 성공).
--
-- 자동 숨김 임계값(신고 N건 누적 시 자동 비공개)은 넣지 않았다. 조직적 신고로 정상 글이 사라지는
-- 위험이 어드민 부재 시간대의 노출보다 크고, 임계값 튜닝은 실데이터 없이는 감이기 때문.
-- 필요해지면 auto_hidden_at 컬럼 하나와 카운트 조건만 얹으면 된다.
CREATE TABLE IF NOT EXISTS `content_report` (
  `id`                  bigint       NOT NULL AUTO_INCREMENT,
  `target_type`         varchar(16)  COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_id`           bigint       NOT NULL,
  `reporter_account_id` bigint       NOT NULL,
  `reason`              varchar(24)  COLLATE utf8mb4_unicode_ci NOT NULL,
  `detail`              varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status`              varchar(16)  COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `created_at`          datetime     DEFAULT NULL,
  `handled_at`          datetime     DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_content_report_once` (`target_type`, `target_id`, `reporter_account_id`),
  KEY `ix_content_report_queue` (`status`, `created_at`),
  CONSTRAINT `fk_content_report_reporter` FOREIGN KEY (`reporter_account_id`) REFERENCES `account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP PROCEDURE pd_add_col;
DROP PROCEDURE pd_add_index;
