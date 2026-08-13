-- V24 — payment_callback_log: 이니시스 콜백 수신 기록(성공·인증실패·위조·승인실패 전부).
--
-- 왜(M-2/M-3): 콜백은 permitAll 인데 수신 사실이 로그로만 남고 DB 엔 0 이었다 — "이니시스는 보냈다는데
-- 우리는 못 받았다" 분쟁, 위조/인증실패 콜백 공격 탐지, 승인실패 콜백의 P_AUTH_TID/P_TID(이니시스에 되물을
-- 유일한 키) 보존이 전부 불가능했다. 이 표가 그 셋을 메운다 — 승인 성패와 무관하게 별도 트랜잭션으로 무조건 남긴다.
--
-- CREATE TABLE IF NOT EXISTS — 멱등(ECS churn 재실행 안전). ix_(order_id) 로 주문별 콜백 이력 조회.

CREATE TABLE IF NOT EXISTS `payment_callback_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `p_status` varchar(8) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `auth_tid` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `tid` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `idc_name` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `outcome` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `received_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_payment_callback_log_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
