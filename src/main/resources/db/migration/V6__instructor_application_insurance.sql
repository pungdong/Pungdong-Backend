-- V6 — 강사 신청에 (선택) 다이빙보험 증빙 이미지 참조 컬럼 추가.
-- 보험은 활동 특화(다이빙보험이 서핑/카약을 커버 안 함)라 계정 공유가 아니라 종목 신청별
-- (InstructorApplication)로 둔다. nullable(옵셔널). 자격증과 동일한 비공개 패턴 — S3 객체 key 저장,
-- 어드민/본인 조회 시 presigned 로 열람(공개 URL 아님).
--
-- 멱등: MySQL 은 ADD COLUMN IF NOT EXISTS 가 없어 information_schema 로 조건부 처리(stored procedure).
-- ECS 재시도/동시실행에도 안전(이미 있으면 no-op). (테스트는 H2 create-drop 이라 엔티티에서 컬럼 생성.)

DROP PROCEDURE IF EXISTS pd_add_col;

DELIMITER //

CREATE PROCEDURE pd_add_col(IN tbl VARCHAR(64), IN col VARCHAR(64), IN ddl VARCHAR(255))
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col) THEN
    SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', ddl);
    PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END //

DELIMITER ;

CALL pd_add_col('instructor_application', 'insurance_file_key', 'VARCHAR(255) NULL');

DROP PROCEDURE pd_add_col;
