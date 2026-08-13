-- V22 — payment_approval: 결제 승인 시도 원장 (환불의 refund_order 와 대칭).
--
-- 왜: 승인은 applyConfirm 안에서 PG 청구를 한 뒤에 주문/회차를 확정한다. 그 확정이 어디선가 롤백되면
-- "카드는 청구됐는데 우리 DB 엔 흔적 0"(주문 READY, paymentKey null)이 돼 대사로도 못 잡고 환불도 못 했다.
-- 환불엔 refund_order 원장이 있는데 승인엔 대응물이 없었다(비대칭). 이제 PG 호출 직전 ATTEMPTED 를, 승인되면
-- APPROVED(+pg_transaction_id)를 별도 트랜잭션에 즉시 커밋해 청구 사실을 durable 하게 남긴다 — 확정이 롤백돼도
-- 재시도가 재청구 없이 그 결과로 전진 확정한다(정확히 한 번 청구 / 여러 번 적용). ATTEMPTED 잔존 = 결과 미확인.
--
-- CREATE TABLE IF NOT EXISTS — 멱등(ECS churn 으로 동시/재실행돼도 안전). ix_(order,status) 로 대사 조회를 받친다.

CREATE TABLE IF NOT EXISTS `payment_approval` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `payment_order_id` bigint DEFAULT NULL,
  `amount` int NOT NULL,
  `provider` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `pg_transaction_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `method` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `approved_at` datetime(6) DEFAULT NULL,
  `attempted_at` datetime(6) DEFAULT NULL,
  `resolved_at` datetime(6) DEFAULT NULL,
  `failure_code` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `failure_message` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_payment_approval_order_status` (`payment_order_id`, `status`),
  CONSTRAINT `fk_payment_approval_order` FOREIGN KEY (`payment_order_id`) REFERENCES `payment_order` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
