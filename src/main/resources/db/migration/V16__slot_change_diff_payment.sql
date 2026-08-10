-- V16 — 슬롯 변경 차액 결제: 주문이 "적용할 목표 슬롯"을 들고, 좌석 hold 가 그 주문에 귀속된다.
--
-- 왜: 더 비싼 시간대로 옮기려면 차액을 받아야 하는데, "결제 대기"를 예약 상태(enrollment_round.status)에 두면
-- 방금 없앤 PAYMENT_PENDING 류가 되살아난다. 대신 대기를 주문에 두면 회차는 내내 ACCEPT_PENDING/CONFIRMED 를
-- 유지하고, 승인되는 순간 슬롯이 교체된다. 학생이 결제를 포기하면 주문만 만료되고 예약은 원래 슬롯 그대로다.
--
-- availability_hold.payment_order_id: 결제창이 떠 있는 동안 목표 슬롯 자리를 잡아두는 hold. proposal_round_id
-- (강사 제안 hold)와 따로 두는 이유 = 제안 TTL 스위퍼가 걷어가면 안 되고 생명주기가 주문에 묶이기 때문.
-- raw BIGINT — availability 가 payment 를 역참조하지 않게(단방향 의존 유지, proposal_round_id 와 같은 방식).
--
-- MySQL 은 ADD COLUMN IF NOT EXISTS 가 없어 information_schema 로 조건부(V2/V10~V15 동일 패턴). 멱등.

DROP PROCEDURE IF EXISTS pd_add_slot_change_cols;

DELIMITER //

CREATE PROCEDURE pd_add_slot_change_cols()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_order'
                       AND COLUMN_NAME = 'target_date') THEN
    ALTER TABLE `payment_order`
      ADD COLUMN `target_date` date DEFAULT NULL,
      ADD COLUMN `target_ticket_ref` varchar(255) DEFAULT NULL,
      ADD COLUMN `target_block_start` time(6) DEFAULT NULL,
      ADD COLUMN `target_block_end` time(6) DEFAULT NULL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'availability_hold'
                       AND COLUMN_NAME = 'payment_order_id') THEN
    ALTER TABLE `availability_hold` ADD COLUMN `payment_order_id` bigint DEFAULT NULL;
  END IF;

  -- 만료 스위프(목표 슬롯 단 READY 주문) · hold 해제(주문별) 조회를 인덱스로 받친다.
  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_order'
                       AND INDEX_NAME = 'ix_payment_order_status_target') THEN
    CREATE INDEX `ix_payment_order_status_target` ON `payment_order` (`status`, `target_date`);
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'availability_hold'
                       AND INDEX_NAME = 'ix_availability_hold_payment_order') THEN
    CREATE INDEX `ix_availability_hold_payment_order` ON `availability_hold` (`payment_order_id`);
  END IF;
END //

DELIMITER ;

CALL pd_add_slot_change_cols();

DROP PROCEDURE IF EXISTS pd_add_slot_change_cols;
