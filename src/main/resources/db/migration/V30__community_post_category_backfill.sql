-- V30 — 게시물 작성 경로 통합: 카테고리 backfill + NOT NULL, 그리고 남아 있던 피드 미노출 행 정리.
--
-- ## 왜 지금
--
-- 작성 폼이 하나로 합쳐졌다(2026-08-18). "브랜딩 글"과 "커뮤니티 글"은 같은 글이고, 다른 건
-- **작성자가 프로필 그리드에도 남길지**(`show_on_profile`) 하나뿐이다. 신규 작성·수정은 전부
-- `POST|PUT /community/posts` 를 타고, 거기는 카테고리가 필수다. 구 `POST /branding/me/posts` 도
-- 같은 PR 에서 카테고리를 필수로 조였다(구버전 앱 호환으로만 남는 경로).
--
-- 그러면 **카테고리 없는 글**이 문제로 남는다: 통합 폼으로 그 글을 수정하려면 카테고리를 골라야 하는데,
-- 원래 없던 분류를 작성자가 발명해야 오타 하나를 고칠 수 있다. 그래서 기존 행을 채우고 컬럼을 조인다.
--
-- ## 왜 TOUR 로 채우나 — "없는 값을 지어내지 않는다" 원칙과 충돌하지 않는가
--
-- 충돌하지 않는다. 채우는 대상이 **실사용자 데이터가 아니다**:
--   - 프로덕션 최종 배포는 a383968(2026-08-09)이고 거기엔 V17(branding_post)·V19(community)가 없다.
--     즉 prod 에는 이 테이블 자체가 없고, 이 UPDATE 가 만나는 행은 0건이다.
--   - 스테이징/로컬의 행은 전부 내부 테스트 글이다.
-- 값으로 TOUR 를 고른 건 대상이 전부 **사진 필수였던 구 브랜딩 게시물**이라 "투어 자랑"이 가장 가깝기
-- 때문이다. 실사용자 글에 임의 분류를 붙이는 상황이었다면 backfill 대신 마이그레이션을 미루고
-- 작성자에게 고르게 했을 것이다.
--
-- ## show_in_feed 도 1 로 올린다
--
-- V19 는 기존 브랜딩 글을 피드에 **소급 노출하지 않았다**(`show_in_feed=0`) — 유저가 동의한 적 없는
-- 노출을 만들지 않기 위해서였다. 그 판단의 전제(실사용자 글)가 위와 같은 이유로 성립하지 않고,
-- 통합 모델에서는 "피드에 없는 글"을 만들 수 있는 쓰기 경로가 아예 없다. 남겨 두면 FE 가 두 축
-- (`is_hidden`·`show_on_profile`)으로 이해하는 노출 규칙에 **화면에서 설명되지 않는 세 번째 축**이
-- 남는다 — 숨기지도 않았는데 피드에 없는 글. 내부 테스트 행을 지금 정렬해 둔다.
-- (컬럼 자체는 남긴다. "프로필 전용 글" 이 필요해지면 다시 쓸 자리이고, 피드 인덱스의 선두 컬럼이다.)
--
-- ## 멱등
--
-- UPDATE 는 조건부라 두 번 돌아도 같은 결과다. MODIFY 는 information_schema 로 이미 NOT NULL 이면
-- 건너뛴다 — 재실행 때 큰 테이블을 다시 재구성하지 않게(ECS 가 실패 태스크를 빠르게 재시작해 같은
-- 마이그레이션이 동시/재시도 실행될 수 있다, 2026-06-28 prod 사고 #121).

-- 1. 카테고리 backfill — NOT NULL 로 조이기 전에 NULL 을 없앤다.
UPDATE `branding_post` SET `category` = 'TOUR' WHERE `category` IS NULL;

-- 2. 피드 미노출로 남아 있던 구 브랜딩 글을 통합 모델에 맞춘다(숨김 여부는 건드리지 않는다).
UPDATE `branding_post` SET `show_in_feed` = b'1' WHERE `show_in_feed` = b'0';

-- 3. category NOT NULL.
DROP PROCEDURE IF EXISTS pd_category_not_null;

DELIMITER //

CREATE PROCEDURE pd_category_not_null()
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'branding_post'
               AND COLUMN_NAME = 'category'
               AND IS_NULLABLE = 'YES') THEN
    ALTER TABLE `branding_post`
      MODIFY COLUMN `category` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL;
  END IF;
END //

DELIMITER ;

CALL pd_category_not_null();

DROP PROCEDURE pd_category_not_null;
