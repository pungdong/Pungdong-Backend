-- V20 — enrollment_round · payment_order 에 낙관적 락 컬럼(version) 추가.
--
-- 왜: 두 엔티티엔 @Version 도 @DynamicUpdate 도 없어, 동시 상태 전이가 "전체 컬럼 blind overwrite"(lost update)였다.
-- 취소↔승인 교차(paid=false 로 오판 → 환불 이벤트 미발행), supersede 가 결제완료 회차를 PENDING 으로 되돌림,
-- 만료 스윕이 DONE 주문을 FAILED 로 덮어 모든 환불 경로에서 안 보이게 함 — 전부 같은 병(blind overwrite)이었다.
-- @Version 을 붙이면 Hibernate 가 UPDATE ... WHERE id=? AND version=? 로 바꿔, 진 쪽 트랜잭션이 롤백된다.
--
-- 기존 행은 version=0 으로 시작(@Version long 의 초기값과 일치). NOT NULL DEFAULT 0.
-- MySQL 은 ADD COLUMN IF NOT EXISTS 가 없어 information_schema 로 조건부(V2/V10~V14 동일 패턴).
-- 멱등 — ECS churn 으로 동시/재실행돼도 안전.

DROP PROCEDURE IF EXISTS pd_add_version_columns;

DELIMITER //

CREATE PROCEDURE pd_add_version_columns()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'enrollment_round'
                       AND COLUMN_NAME = 'version') THEN
    ALTER TABLE `enrollment_round` ADD COLUMN `version` BIGINT NOT NULL DEFAULT 0;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_order'
                       AND COLUMN_NAME = 'version') THEN
    ALTER TABLE `payment_order` ADD COLUMN `version` BIGINT NOT NULL DEFAULT 0;
  END IF;
END //

DELIMITER ;

CALL pd_add_version_columns();

DROP PROCEDURE IF EXISTS pd_add_version_columns;
