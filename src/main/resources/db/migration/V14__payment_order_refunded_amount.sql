-- V14 — payment_order 에 누적 환불액(refunded_amount) 추가 + 기존 행 백필.
--
-- 왜: 승인 사실인 status 는 환불해도 DONE 이라, 이 컬럼이 없으면 "이 주문 환불됐나 / 얼마 남았나"를
-- refund_order 집계로만 알 수 있다(CS·회계에서 테이블을 눈으로 못 읽음). 잔액을 행에 들고 있게 한다.
-- refund_order(이력)가 원장이고 이 컬럼은 그 합의 캐시 — 어긋나면 refund_order 가 진실이다.
--
-- 상태 규약(이 마이그레이션 이후): DONE+refunded=0 정상 / DONE+refunded>0 부분환불 / CANCELED 전액환불.
--
-- MySQL 은 ADD COLUMN IF NOT EXISTS 가 없어 information_schema 로 조건부(V2/V10/V11/V12 동일 패턴).
-- 멱등 — ECS churn 으로 동시/재실행돼도 안전.

DROP PROCEDURE IF EXISTS pd_add_refunded_amount;

DELIMITER //

CREATE PROCEDURE pd_add_refunded_amount()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_order'
                       AND COLUMN_NAME = 'refunded_amount') THEN
    ALTER TABLE `payment_order` ADD COLUMN `refunded_amount` INT NOT NULL DEFAULT 0;

    -- 백필: 기존 DONE 환불 이력의 합. (컬럼을 새로 만든 경우에만 = 재실행 시 누적 오염 없음)
    UPDATE `payment_order` o
      SET o.refunded_amount = COALESCE((
        SELECT SUM(r.amount) FROM `refund_order` r
         WHERE r.payment_order_id = o.id AND r.status = 'DONE'), 0);

    -- 전액 환불된 기존 주문은 상태도 규약에 맞춰 CANCELED 로 수렴.
    UPDATE `payment_order`
       SET status = 'CANCELED'
     WHERE status = 'DONE' AND refunded_amount >= amount AND amount > 0;
  END IF;
END //

DELIMITER ;

CALL pd_add_refunded_amount();

DROP PROCEDURE IF EXISTS pd_add_refunded_amount;
