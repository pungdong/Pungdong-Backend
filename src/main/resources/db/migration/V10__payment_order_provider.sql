-- V10 — payment_order 에 provider(결제 당시 PG) 추가.
--
-- 왜: 전역 설정(pungdong.payment.mode)은 "신규 주문을 어느 PG 로 보낼지"만 정한다. 주문은 설정보다 오래 산다 —
-- KCP 로 결제한 뒤 토스로 전환하면 그 주문의 승인·환불은 여전히 KCP 로 가야 한다. 전역 설정으로 라우팅하면
-- 존재하지 않는 거래에 취소를 보내 "돈은 받았는데 환불은 실패"가 된다(FE 리뷰에서 지적, PR #183).
--
-- legacy 행은 NULL 로 남긴다 — 애플리케이션이 NULL 일 때만 현재 활성 게이트웨이로 폴백한다.
-- (결제는 아직 라이브가 아니라 실 결제 행이 없다. 임의 값으로 백필하면 오히려 잘못된 PG 로 라우팅될 수 있어 하지 않는다.)
--
-- MySQL 은 ADD COLUMN IF NOT EXISTS 가 없어 information_schema 로 조건부 처리(V2 와 동일 패턴).
-- 멱등 필수 — ECS 가 실패한 태스크를 빠르게 재시작해 같은 마이그레이션이 동시/재시도 실행될 수 있다(#121 사고).

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

-- STUB / TOSS / KCP (PaymentProvider enum, @Enumerated(STRING))
CALL pd_add_col('payment_order', 'provider', 'VARCHAR(16) NULL');

DROP PROCEDURE IF EXISTS pd_add_col;
