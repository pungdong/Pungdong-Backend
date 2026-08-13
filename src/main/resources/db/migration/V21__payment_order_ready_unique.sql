-- V21 — payment_order: 회차당 READY 주문 최대 1개 (동시 prepare 이중 주문·이중 청구·영구 500 방지).
--
-- 왜: prepare 가 findByEnrollmentRoundIdAndStatus(READY) → 없으면 save 인 락 없는 read-then-write 라,
-- 동시 prepare 둘이 각각 READY 주문을 만들 수 있었다. 그 회차는 이후 조회가 IncorrectResultSize 로 500(영구),
-- 두 주문이 각각 승인되면 이중 청구. MySQL 은 부분(조건부) 유니크가 없어 — status='READY' 일 때만 값을 갖는
-- 가상 생성 컬럼 + 그 컬럼 UNIQUE 로 "회차당 READY 1개"를 DB 가 강제한다(비-READY 는 NULL → 다중 NULL 허용).
--
-- 인덱스 추가 전, 과거 race 로 생긴 중복 READY 를 정리(회차별 최신 max(id) 1개 유지, 나머지 FAILED) —
-- 안 하면 ADD UNIQUE 가 중복 때문에 실패한다. FAILED = 승인 안 된 주문이라 좌석 hold 는 만료 스윕이 정리.
-- MySQL information_schema 멱등 패턴(V2/V10~V14/V20). ECS churn 재실행 안전.

DROP PROCEDURE IF EXISTS pd_add_ready_round_unique;

DELIMITER //

CREATE PROCEDURE pd_add_ready_round_unique()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_order'
                       AND COLUMN_NAME = 'ready_enrollment_round_id') THEN
    ALTER TABLE `payment_order`
      ADD COLUMN `ready_enrollment_round_id` BIGINT
        GENERATED ALWAYS AS (CASE WHEN `status` = 'READY' THEN `enrollment_round_id` END) VIRTUAL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_order'
                       AND INDEX_NAME = 'uk_payment_order_ready_round') THEN
    -- 회차별 READY 가 2건 이상이면 최신 1건만 남기고 나머지를 FAILED 로(중복 제거 → 유니크 추가 가능).
    UPDATE `payment_order` po
      JOIN (
        SELECT id FROM (
          SELECT id, ROW_NUMBER() OVER (PARTITION BY enrollment_round_id ORDER BY id DESC) AS rn
            FROM `payment_order`
           WHERE status = 'READY' AND enrollment_round_id IS NOT NULL
        ) ranked WHERE ranked.rn > 1
      ) dup ON dup.id = po.id
      SET po.status = 'FAILED';

    ALTER TABLE `payment_order`
      ADD UNIQUE INDEX `uk_payment_order_ready_round` (`ready_enrollment_round_id`);
  END IF;
END //

DELIMITER ;

CALL pd_add_ready_round_unique();

DROP PROCEDURE IF EXISTS pd_add_ready_round_unique;
