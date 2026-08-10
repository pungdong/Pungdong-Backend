-- V15 — refund_order 를 "성공 기록"에서 "시도 원장"으로: 결과 확정 시각 + PG 실패 진단정보.
--
-- 왜: 지금까지 환불은 성공(DONE)만 저장됐다. PG 가 거절하면 예외 전파로 트랜잭션이 롤백돼 행이 아예 안 남아
-- ① 재시도 근거가 없고 ② PG 원장과 대사(reconciliation)를 못 하며 ③ 가장 위험하게는 "PG 엔 취소가 됐는데
-- 우리 DB 엔 없는" 부분실패를 탐지할 수 없었다. 이제 PG 호출 직전 REQUESTED 로 선기록(별도 트랜잭션)하고
-- 결과에 따라 DONE/FAILED 로 확정한다 — REQUESTED 로 남은 행 = 결과 미확인 = 대사 대상.
--
-- 잔액(payment_order.refunded_amount)은 DONE 만 센다. 기존 행은 전부 성공분이므로 completed_at 을
-- created_at 으로 채워 준다(그 시점엔 요청=완료였다).
--
-- MySQL 은 ADD COLUMN IF NOT EXISTS 가 없어 information_schema 로 조건부(V2/V10~V14 동일 패턴). 멱등.

DROP PROCEDURE IF EXISTS pd_add_refund_attempt_cols;

DELIMITER //

CREATE PROCEDURE pd_add_refund_attempt_cols()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_order'
                       AND COLUMN_NAME = 'completed_at') THEN
    ALTER TABLE `refund_order` ADD COLUMN `completed_at` datetime(6) DEFAULT NULL;
    -- 기존 행은 모두 성공 확정분 — 요청 시각을 완료 시각으로 본다.
    UPDATE `refund_order` SET completed_at = created_at WHERE completed_at IS NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_order'
                       AND COLUMN_NAME = 'failure_code') THEN
    ALTER TABLE `refund_order` ADD COLUMN `failure_code` varchar(32) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_order'
                       AND COLUMN_NAME = 'failure_message') THEN
    ALTER TABLE `refund_order` ADD COLUMN `failure_message` varchar(255) DEFAULT NULL;
  END IF;

  -- 대사 조회(그 주문의 미확정 시도가 있나)를 인덱스로 받친다.
  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_order'
                       AND INDEX_NAME = 'ix_refund_order_order_status') THEN
    CREATE INDEX `ix_refund_order_order_status` ON `refund_order` (`payment_order_id`, `status`);
  END IF;
END //

DELIMITER ;

CALL pd_add_refund_attempt_cols();

DROP PROCEDURE IF EXISTS pd_add_refund_attempt_cols;
