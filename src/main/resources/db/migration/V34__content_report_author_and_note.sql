-- V34 — 신고 큐가 "누구에 대한 신고인지" 를 잃지 않게 하는 컬럼 둘.
--
-- ## 1. content_report.target_author_account_id — 접수 시점에 고정하는 조치 대상
--
-- 지금 어드민 큐는 행마다 대상(글·댓글·강의·메시지)을 열어 작성자를 알아낸다. 대상이 지워지면
-- 작성자가 null 이 되어 **그 신고는 영구히 "누구에 대한 신고인지 모르는 행"** 이 된다. 접수 시점엔
-- 이미 작성자를 확인하고 있으므로(자기 것 신고 차단이 그걸로 판정된다) 그 값을 그대로 적어 둔다.
--
-- 부수효과가 본래 목적만큼 크다: 대상이 넷으로 흩어진 탓에 **같은 강사의 여러 강의에 걸친 반복 신고가
-- 큐에서 안 보였다**(강의 3개에 1건씩이면 어디서도 안 걸린다). 컬럼 하나로 작성자 단위 집계·필터가
-- 한 쿼리가 된다.
--
-- FK 는 걸지 않는다 — 이 테이블은 폴리모픽 참조라 원래 FK 가 없고(target_type/target_id),
-- 계정 삭제(익명화) 경로가 신고 행 때문에 막히면 안 된다.
--
-- ## 2. content_report.admin_note — 처리 시점의 판단 근거
--
-- 조치(ACTIONED)는 대상별로 무겁다 — 강의면 둘러보기에서 빠지고 신규 신청이 막힌다(= 사실상 판매
-- 중단). 1:1 분쟁엔 과해서 어드민이 실제로는 기각(DISMISSED)을 누르게 되는데, 그러면 "문제없음" 으로만
-- 남아 **이력이 거짓이 된다**("강사에게 경고 전달함" 과 "문제없음" 이 같은 행으로 보인다).
-- 자유 메모 한 칸이면 그 갭이 메워진다. 비파괴 조치 상태값(확인함/경고/중재중)을 늘리는 건 별도다 —
-- 그건 나중에 추가해도 과거 신고에 소급 손실이 없지만, 메모는 그 순간에만 존재하는 정보다.
--
-- 멱등: information_schema 로 존재를 확인하고 추가한다(V2·V33 과 같은 패턴). ECS 롤링/재시도로 같은
-- 마이그레이션이 동시·반복 실행돼도 1060(duplicate column)으로 실패하지 않는다.

DROP PROCEDURE IF EXISTS pd_v34_add_col;
DROP PROCEDURE IF EXISTS pd_v34_add_idx;

DELIMITER //

CREATE PROCEDURE pd_v34_add_col(IN tbl VARCHAR(64), IN col VARCHAR(64), IN ddl TEXT)
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col) THEN
    SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', ddl);
    PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END //

CREATE PROCEDURE pd_v34_add_idx(IN tbl VARCHAR(64), IN idx VARCHAR(64), IN cols TEXT)
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND INDEX_NAME = idx) THEN
    SET @sql = CONCAT('CREATE INDEX `', idx, '` ON `', tbl, '` (', cols, ')');
    PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END //

DELIMITER ;

CALL pd_v34_add_col('content_report', 'target_author_account_id', 'bigint DEFAULT NULL');
CALL pd_v34_add_col('content_report', 'admin_note', 'varchar(500) DEFAULT NULL');

-- 작성자 단위 집계·필터가 타는 인덱스.
CALL pd_v34_add_idx('content_report', 'ix_content_report_target_author', '`target_author_account_id`');

DROP PROCEDURE IF EXISTS pd_v34_add_col;
DROP PROCEDURE IF EXISTS pd_v34_add_idx;

-- 이미 접수된 신고의 작성자를 대상 테이블에서 되찾는다. 대상이 살아 있는 동안에만 가능한 일이라
-- 지금 채워 둔다 — 안 채우면 기존 행은 대상이 지워지는 순간 영영 작성자를 알 수 없게 된다.
-- 재실행해도 target_author_account_id IS NULL 인 행만 건드리므로 멱등이다.

UPDATE `content_report` r
JOIN `branding_post` p ON p.`id` = r.`target_id`
JOIN `account_branding` b ON b.`id` = p.`branding_id`
SET r.`target_author_account_id` = b.`account_id`
WHERE r.`target_type` = 'POST' AND r.`target_author_account_id` IS NULL;

UPDATE `content_report` r
JOIN `community_comment` c ON c.`id` = r.`target_id`
SET r.`target_author_account_id` = c.`account_id`
WHERE r.`target_type` = 'COMMENT' AND r.`target_author_account_id` IS NULL;

UPDATE `content_report` r
JOIN `course` c ON c.`id` = r.`target_id`
SET r.`target_author_account_id` = c.`instructor_id`
WHERE r.`target_type` = 'COURSE' AND r.`target_author_account_id` IS NULL;

UPDATE `content_report` r
JOIN `chat_message` m ON m.`id` = r.`target_id`
SET r.`target_author_account_id` = m.`sender_account_id`
WHERE r.`target_type` = 'CHAT_MESSAGE' AND r.`target_author_account_id` IS NULL;
