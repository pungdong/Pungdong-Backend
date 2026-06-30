-- V6 — identity_verification 에 통신사·내외국인 구분 컬럼 추가 (2026-06-30).
--   carrier        : 통신사(SKT/KT/LGU+ 등). 통신사 본인확인기관 반환 속성.
--   foreigner_type : 내·외국인 구분(DOMESTIC/FOREIGN). 본인확인기관 반환 속성.
-- 처리방침 본인인증 수집 항목("휴대전화번호, 통신사, 내·외국인 구분")이 실제 저장 컬럼과
-- 1:1 대응하도록 BE 정합성을 맞춘다. 정책 전문 자체는 Sanity legalDocument(slug=privacy).
--
-- 멱등(이미 컬럼 있으면 no-op) — MySQL 은 ADD COLUMN IF NOT EXISTS 가 없어 information_schema 가드.
-- ECS churn 으로 같은 마이그레이션이 동시/재시도 실행돼도 1060(duplicate column)로 실패하지 않게. (패턴은 V4)

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

CALL pd_add_col('identity_verification', 'carrier',
                '`carrier` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL');
CALL pd_add_col('identity_verification', 'foreigner_type',
                '`foreigner_type` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL');

DROP PROCEDURE pd_add_col;
