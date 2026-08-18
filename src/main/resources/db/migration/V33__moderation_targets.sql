-- V33 — 신고 대상 확장(강의·채팅 메시지)에 필요한 두 컬럼.
--
-- ## 1. branding_post.moderated_at — 순환 의존을 끊기 위한 컬럼
--
-- 신고 코드가 community/ 를 떠나 moderation/ 으로 가는데, 지금은 community 가 신고를 **읽는다**:
-- CommunityPostService.updateHidden 이 "어드민이 조치한 글인가" 를 content_report 에서 확인해
-- 작성자가 조치를 되살리지 못하게 막는다. 그대로 옮기면 community → moderation → community 순환이다.
--
-- 해법은 조치 사실을 **대상 도메인의 컬럼**에 남기고, 대상 도메인이 자기 컬럼만 보게 하는 것이다.
-- 어드민이 ACTIONED 를 누르면 is_hidden 과 함께 moderated_at 을 세우고, 작성자의 공개 전환은
-- moderated_at IS NOT NULL 만 본다. 교차 읽기가 하나 줄어드는 순수한 개선이기도 하다.
--
-- 백필: 이미 ACTIONED 인 신고가 가리키는 글에 그 신고의 handled_at 을 채운다. 안 채우면
-- **이미 조치된 글을 작성자가 되살릴 수 있게 되어** 마이그레이션이 보안 후퇴가 된다.
-- (prod 에는 커뮤니티 테이블 자체가 없어 대상은 staging 뿐이지만, 규칙은 환경과 무관하다.)
--
-- ## 2. course.blocked_at — 어드민 전용 강의 차단
--
-- 강의 신고(ReportTargetType.COURSE)의 조치는 "실제로 숨긴다" 여야 한다(이 레포의 불변식 —
-- 상태만 바꾸고 콘텐츠가 살아 있으면 조치가 아니다). 그런데 CourseStatus(DRAFT/OPEN/CLOSED)는
-- **강사가 스스로 바꾸는 영업 상태**라 CLOSED 로 내리면 강사가 즉시 되돌린다. 그래서 강사 UI 가
-- 건드릴 수 없는 별도 축을 둔다.
--
-- 효과는 "둘러보기·상세에서 사라지고 신규 신청이 막힌다" 까지다. **이미 확정·결제된 수강은
-- 건드리지 않는다** — 레포의 "확정 취소 없음" 원칙이고, 돈이 오간 관계를 어드민 조치가 일방적으로
-- 끊으면 환불·분쟁 문제가 된다. 그 정리는 환불 경로가 따로 한다.
--
-- 멱등: 두 컬럼 모두 information_schema 로 존재를 확인하고 추가한다(V2·V19 와 같은 패턴).
-- ECS 롤링/재시도로 같은 마이그레이션이 동시·반복 실행돼도 1060(duplicate column)으로 실패하지 않는다.

DROP PROCEDURE IF EXISTS pd_v33_add_col;

DELIMITER //

CREATE PROCEDURE pd_v33_add_col(IN tbl VARCHAR(64), IN col VARCHAR(64), IN ddl TEXT)
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col) THEN
    SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', ddl);
    PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END //

DELIMITER ;

CALL pd_v33_add_col('branding_post', 'moderated_at', 'datetime DEFAULT NULL');
CALL pd_v33_add_col('course', 'blocked_at', 'datetime DEFAULT NULL');

DROP PROCEDURE IF EXISTS pd_v33_add_col;

-- 이미 조치된 글에 표식을 채운다. 신고가 여러 건이면 가장 이른 조치 시각을 쓴다(그때 내려간 것이므로).
-- 재실행해도 moderated_at IS NULL 인 행만 건드리므로 멱등이다.
UPDATE `branding_post` p
SET p.`moderated_at` = (
  SELECT MIN(r.`handled_at`) FROM `content_report` r
  WHERE r.`target_type` = 'POST' AND r.`target_id` = p.`id` AND r.`status` = 'ACTIONED'
)
WHERE p.`moderated_at` IS NULL
  AND EXISTS (
    SELECT 1 FROM `content_report` r
    WHERE r.`target_type` = 'POST' AND r.`target_id` = p.`id` AND r.`status` = 'ACTIONED'
  );
