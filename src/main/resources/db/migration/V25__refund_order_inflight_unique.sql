-- V25 — refund_order: 한 주문당 in-flight(REQUESTED) 환불 시도 최대 1개 (동시 이중환불 방지, H-1).
--
-- 왜: applyCancel 은 refundable 조회 → hasUnresolvedAttempt 가드 → recordAttempt(REQUESTED) 인 락 없는
-- check-then-insert 다. 두 환불 발행자(학생 취소·강사 거절·만료 스윕)가 near-simultaneous 로 들어오면 둘 다
-- 가드를 통과(아직 서로의 REQUESTED 를 못 봄)한 뒤 각각 PG 취소를 호출 → 이중환불(돈 두 번 나감). @Version 낙관
-- 락은 못 막는다 — markDone 이 REQUIRES_NEW 로 주문을 재조회·갱신해 두 스레드가 각자 refundedAmount 를 올린다.
-- 비관 락(SELECT FOR UPDATE)도 못 쓴다 — markDone(REQUIRES_NEW, 다른 커넥션)이 같은 행을 UPDATE 하려다
-- 바깥 트랜잭션의 FOR UPDATE 와 lock-wait 로 self-deadlock 난다. 그래서 RC4(V21 READY 유니크)와 동일하게
-- DB 조건부 유니크로 원자적으로 막는다: status='REQUESTED' 일 때만 값을 갖는 가상 생성 컬럼 + 그 컬럼 UNIQUE.
-- 비-REQUESTED(DONE/FAILED)는 NULL → 한 주문의 과거 환불 여러 건은 공존 가능(부분환불 후 잔액환불). 동시 두 번째
-- recordAttempt 는 유니크 위반 → 그 발행자 트랜잭션 롤백(가드가 던지는 RefundBlockedException 과 같은 결과).
--
-- 인덱스 추가 전, 혹시 과거 race 로 생긴 중복 REQUESTED 를 정리(주문별 최신 max(id) 1개만 REQUESTED 유지,
-- 나머지는 FAILED — 결과 미확인이라 사람이 PG 원장과 대사) — 안 하면 ADD UNIQUE 가 중복 때문에 실패한다.
-- MySQL information_schema 멱등 패턴(V2/V10~V14/V20/V21). ECS churn 재실행 안전.

DROP PROCEDURE IF EXISTS pd_add_refund_inflight_unique;

DELIMITER //

CREATE PROCEDURE pd_add_refund_inflight_unique()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_order'
                       AND COLUMN_NAME = 'inflight_payment_order_id') THEN
    ALTER TABLE `refund_order`
      ADD COLUMN `inflight_payment_order_id` BIGINT
        GENERATED ALWAYS AS (CASE WHEN `status` = 'REQUESTED' THEN `payment_order_id` END) VIRTUAL;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_order'
                       AND INDEX_NAME = 'uk_refund_order_inflight') THEN
    -- 주문별 REQUESTED 가 2건 이상이면 최신 1건만 남기고 나머지를 FAILED 로(중복 제거 → 유니크 추가 가능).
    UPDATE `refund_order` ro
      JOIN (
        SELECT id FROM (
          SELECT id, ROW_NUMBER() OVER (PARTITION BY payment_order_id ORDER BY id DESC) AS rn
            FROM `refund_order`
           WHERE status = 'REQUESTED' AND payment_order_id IS NOT NULL
        ) ranked WHERE ranked.rn > 1
      ) dup ON dup.id = ro.id
      SET ro.status = 'FAILED',
          ro.failure_code = 'DEDUP',
          ro.failure_message = 'V25 중복 REQUESTED 정리 — PG 원장 대사 필요';

    ALTER TABLE `refund_order`
      ADD UNIQUE INDEX `uk_refund_order_inflight` (`inflight_payment_order_id`);
  END IF;
END //

DELIMITER ;

CALL pd_add_refund_inflight_unique();

DROP PROCEDURE IF EXISTS pd_add_refund_inflight_unique;
