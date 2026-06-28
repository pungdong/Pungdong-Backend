-- V2 — enrollment 다회차 분리(2026-06-28, Enrollment ⊃ EnrollmentRound) 후 enrollment 테이블에 남은
-- 옛 단일회차 컬럼 제거. 슬롯·부대비용 컬럼은 EnrollmentRound 로 내려갔는데 hbm2ddl=update 가 컬럼을 안 지워
-- 남았고(특히 entry_snapshot NOT NULL → 신청 INSERT 500), 이 마이그레이션이 모든 환경을 수렴시킨다.
--
-- 로컬은 이미 수동 정리됨 → 조건부라 no-op. staging/prod 의 drift 를 여기서 제거.
-- MySQL 은 DROP COLUMN/FK IF EXISTS 가 없어 information_schema 로 조건부 처리(stored procedure).

DROP PROCEDURE IF EXISTS pd_drop_col;
DROP PROCEDURE IF EXISTS pd_drop_fk_on_col;

DELIMITER //

CREATE PROCEDURE pd_drop_col(IN tbl VARCHAR(64), IN col VARCHAR(64))
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col) THEN
    SET @sql = CONCAT('ALTER TABLE `', tbl, '` DROP COLUMN `', col, '`');
    PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END //

-- FK 이름은 환경마다 같지만(Hibernate 결정적), 안전하게 컬럼으로 찾아 드롭.
CREATE PROCEDURE pd_drop_fk_on_col(IN tbl VARCHAR(64), IN col VARCHAR(64))
BEGIN
  DECLARE fkname VARCHAR(64);
  SELECT CONSTRAINT_NAME INTO fkname FROM information_schema.KEY_COLUMN_USAGE
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
          AND REFERENCED_TABLE_NAME IS NOT NULL
    LIMIT 1;
  IF fkname IS NOT NULL THEN
    SET @sql = CONCAT('ALTER TABLE `', tbl, '` DROP FOREIGN KEY `', fkname, '`');
    PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END //

DELIMITER ;

-- session_id 는 FK 먼저 끊고 컬럼 드롭
CALL pd_drop_fk_on_col('enrollment', 'session_id');
CALL pd_drop_col('enrollment', 'session_id');

-- 슬롯·상태·부대비용 옛 컬럼(모두 EnrollmentRound 로 이전됨)
CALL pd_drop_col('enrollment', 'block_end');
CALL pd_drop_col('enrollment', 'block_start');
CALL pd_drop_col('enrollment', 'date');
CALL pd_drop_col('enrollment', 'entry_snapshot');
CALL pd_drop_col('enrollment', 'equipment_snapshot');
CALL pd_drop_col('enrollment', 'rejection_reason');
CALL pd_drop_col('enrollment', 'responded_at');
CALL pd_drop_col('enrollment', 'round_index');
CALL pd_drop_col('enrollment', 'status');
CALL pd_drop_col('enrollment', 'ticket_ref');
CALL pd_drop_col('enrollment', 'venue_ref_id');

DROP PROCEDURE pd_drop_col;
DROP PROCEDURE pd_drop_fk_on_col;
