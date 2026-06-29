-- V5 — application_certificate 의 파일 컬럼명 정렬 (2026-06-29).
-- #138 이 엔티티 필드 fileURL → fileKey 로 바꾸며 @Column(name="file_url") 로 지정했는데,
-- V1 baseline 의 실제 컬럼명은 (옛 필드 fileURL 의 Hibernate 네이밍 결과) `fileurl` 이었다.
-- 엔티티는 file_url 을 기대 / DB 엔 fileurl 만 존재 → hbm2ddl=validate 부팅 크래시.
-- 이 마이그레이션이 fileurl → file_url 로 리네임해 수렴시킨다(데이터 보존).
--
-- MySQL 은 RENAME/CHANGE COLUMN 에 IF EXISTS 가 없어 information_schema 로 조건부 처리(멱등).
-- ECS 재시도/동시실행에도 안전(이미 file_url 이면 no-op). 패턴은 V2 와 동일.

DROP PROCEDURE IF EXISTS pd_rename_col;

DELIMITER //

CREATE PROCEDURE pd_rename_col(IN tbl VARCHAR(64), IN oldc VARCHAR(64),
                               IN newc VARCHAR(64), IN coldef VARCHAR(255))
BEGIN
  -- newc 가 이미 있으면(재실행) 아무것도 안 함. oldc 가 있을 때만 rename.
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = newc)
     AND EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = oldc) THEN
    SET @sql = CONCAT('ALTER TABLE `', tbl, '` CHANGE `', oldc, '` `', newc, '` ', coldef);
    PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END //

DELIMITER ;

CALL pd_rename_col('application_certificate', 'fileurl', 'file_url',
                   'VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL');

DROP PROCEDURE pd_rename_col;
