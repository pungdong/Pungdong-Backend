-- V9 — UTC 전환 컷오버 데이터 리셋 (2026-07-11).
-- #174 에서 instant 를 UTC(OffsetDateTime)로 통일하고 datasource TZ 를 connectionTimeZone=UTC 로 바꿨다.
-- 옛 config(serverTimezone=Asia/Seoul)로 쓰인 row 는 KST-wall 이라 UTC 로 읽으면 9h 어긋난다.
-- 실 유저 0·실 결제 0(pre-launch) + 옛 데이터가 #166 전후로 UTC-wall/KST-wall 섞여 근사 보정(-9h)도
-- 불완전 → 전 데이터를 wipe 하고 UTC 로 재시딩(테스트 스크립트·재가입)한다.
--
-- 안전:
--   • 동적 truncate(현 스키마 base 테이블 전부, 표 추가돼도 안전). FK 순서는 FOREIGN_KEY_CHECKS=0 로 무시.
--   • flyway_schema_history 는 제외(안 그러면 Flyway 이력 소실).
--   • fresh DB 엔 no-op(빈 테이블 truncate). Flyway 가 1회만 실행(tracking)이라 이후 배포에서 재-wipe 안 함.
--   • Sanity(별개 호스티드 CMS: 약관·공식 venue·자격증 단체·siteSettings)는 MySQL 밖이라 무관.

DROP PROCEDURE IF EXISTS pd_truncate_all;

DELIMITER //

CREATE PROCEDURE pd_truncate_all()
BEGIN
  DECLARE done INT DEFAULT 0;
  DECLARE tname VARCHAR(255);
  DECLARE cur CURSOR FOR
    SELECT TABLE_NAME FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_TYPE = 'BASE TABLE'
      AND TABLE_NAME <> 'flyway_schema_history';
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;

  SET FOREIGN_KEY_CHECKS = 0;
  OPEN cur;
  truncate_loop: LOOP
    FETCH cur INTO tname;
    IF done = 1 THEN LEAVE truncate_loop; END IF;
    SET @sql = CONCAT('TRUNCATE TABLE `', tname, '`');
    PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
  END LOOP;
  CLOSE cur;
  SET FOREIGN_KEY_CHECKS = 1;
END //

DELIMITER ;

CALL pd_truncate_all();

DROP PROCEDURE pd_truncate_all;
