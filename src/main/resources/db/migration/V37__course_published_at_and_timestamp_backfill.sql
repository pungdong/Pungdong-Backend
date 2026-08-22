-- V37 — 웹 SEO 대응(BE #322 · #323). 컬럼 하나 + 시각 백필 셋.
--
-- ## 1. course.published_at — "한 번이라도 공개된 적 있나"
--
-- BE #322 로 **마감(CLOSED)된 강의의 공개 상세가 400 이 아니라 200** 이 된다. 웹에서 강의 URL 은
-- 판매 화면이기 전에 색인 자산이라, 마감과 함께 404 가 되면 그 페이지가 쌓은 검색 신뢰도가 같이
-- 사라지고 공유 링크가 죽는다(게다가 404 가 반복되면 크롤러가 /courses/* 재방문 빈도를 낮춰
-- 살아있는 다른 강의의 색인까지 늦어진다).
--
-- 그런데 판정을 status = 'CLOSED' 로 하면 안 된다. CourseStatus 전이는 자유라 **DRAFT → CLOSED
-- 직행**이 가능하고, 그건 마감된 강의가 아니라 **한 번도 발행된 적 없는 초안**이다 — 지킬 색인
-- 자산이 애초에 없고, 열면 강사가 공개를 선택한 적 없는 내용이 노출된다. 그래서 상태가 아니라
-- **발행 이력**을 본다. 최초 OPEN 전환 때 한 번 찍고 이후엔 되돌리지 않는다.
--
-- 백필: 이미 발행 이력이 있는 행(현재 OPEN 이거나 CLOSED)에 근사값을 채운다. 안 채우면
-- **마이그레이션 이전에 마감된 강의가 전부 계속 400** 이라 이 피처가 기존 데이터에 안 먹는다.
-- ⚠️ 이 근사값은 CLOSED 행에선 사실상 **마감 시각**이다. 용도는 불리언("발행된 적 있나") 하나뿐이니
-- JSON-LD datePublished 같은 **날짜로 노출하지 말 것**(Course.publishedAt Javadoc 에도 박아 뒀다).
--
-- ## 2. created_at / updated_at 백필 — lastmod 를 낼 수 있게
--
-- BE #323. sitemap 의 <lastmod> 는 크롤러에게 "이 URL 은 이때 바뀌었다" 를 알려 **바뀐 것만** 다시
-- 가져가게 한다. 그런데 course 의 두 시각은 서비스가 손으로 세팅하는 스타일이라 새고 있었다 —
-- 특히 updated_at 은 한 번도 수정 안 된 강의에서 NULL 이다. 엔티티는 이 PR 에서 @PrePersist/
-- @PreUpdate(레포 표준, AccountBranding 이 정본)로 옮겨 신규 행을 보장하고, 기존 행은 여기서 채운다.
-- 이게 types.ts 의 CourseCardResponse.createdAt 을 옵셔널에서 필수로 승격하는 전제다.
--
-- NOT NULL 로 조이지는 않는다 — 데모 시더가 raw SQL 로 넣는 경로가 있어 부팅 실패 위험이 있고,
-- 보장은 @PrePersist + 이 백필로 충분하다.
--
-- ## 멱등
--
-- 컬럼 추가는 information_schema 확인(V2·V19·V33 과 같은 패턴), UPDATE 는 전부 IS NULL 조건이라
-- 재실행해도 no-op 이다. ECS 롤링/재시도로 같은 마이그레이션이 동시·반복 실행돼도 1060(duplicate
-- column)으로 실패하지 않는다 — 한 번 실패로 기록되면 이후 모든 부팅이 막힌다(#121).

DROP PROCEDURE IF EXISTS pd_v37_add_col;

DELIMITER //

CREATE PROCEDURE pd_v37_add_col(IN tbl VARCHAR(64), IN col VARCHAR(64), IN ddl TEXT)
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col) THEN
    SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', ddl);
    PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END //

DELIMITER ;

CALL pd_v37_add_col('course', 'published_at', 'datetime DEFAULT NULL');

DROP PROCEDURE IF EXISTS pd_v37_add_col;

-- 발행 이력 백필. status 는 @Enumerated(EnumType.STRING) 이라 varchar 로 저장된다.
UPDATE `course`
SET `published_at` = COALESCE(`updated_at`, `created_at`)
WHERE `published_at` IS NULL
  AND `status` IN ('OPEN', 'CLOSED');

-- 시각 백필. created_at 이 NULL 인 행은 updated_at 을, 둘 다 없으면 지금을 쓴다(근사).
UPDATE `course` SET `created_at` = COALESCE(`updated_at`, UTC_TIMESTAMP()) WHERE `created_at` IS NULL;
UPDATE `course` SET `updated_at` = `created_at` WHERE `updated_at` IS NULL;
