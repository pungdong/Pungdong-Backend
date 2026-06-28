-- V4 — 회원탈퇴(soft delete) 익명화 파이프라인(2026-06-29). account 에 탈퇴/익명화 시각 추가.
--   deleted_at    : 탈퇴 시각(유예기간 기준점). isDeleted=true 와 함께 기록.
--   anonymized_at : PII 익명화 완료 시각(null=유예 내 복구가능, non-null=파기완료·복구불가).
-- 정책·보존 항목·법적 근거는 docs/features/account-deletion.md.
--
-- 멱등(이미 컬럼 있으면 no-op) — MySQL 은 ADD COLUMN IF NOT EXISTS 가 없어 information_schema 가드.
-- ECS churn 으로 같은 마이그레이션이 동시/재시도 실행돼도 1060(duplicate column)로 실패하지 않게.

DROP PROCEDURE IF EXISTS pd_add_col;

DELIMITER //

CREATE PROCEDURE pd_add_col(IN tbl VARCHAR(64), IN col VARCHAR(64), IN ddl VARCHAR(255))
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col) THEN
    SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN ', ddl);
    PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END //

DELIMITER ;

CALL pd_add_col('account', 'deleted_at',    '`deleted_at` datetime DEFAULT NULL');
CALL pd_add_col('account', 'anonymized_at', '`anonymized_at` datetime DEFAULT NULL');

DROP PROCEDURE pd_add_col;
