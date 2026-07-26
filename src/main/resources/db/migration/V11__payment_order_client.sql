-- V11 — payment_order 에 client(결제 시작 클라이언트: WEB/APP) 추가.
--
-- 왜: KCP 표준결제는 결제창이 인증결과를 Ret_URL 로 form POST 하는데, 앱(WebView)은 POST 본문을 못 읽는다.
-- 그래서 KCP 는 Ret_URL=BE 로 두고 BE 가 승인 후 GET 리다이렉트 — 이때 web URL 로 갈지 app 스킴(plop://)으로
-- 갈지를 주문에 박제된 이 값으로 고른다(FE 핑퐁 #1~#3). mobile(결제창 레이아웃)과 독립 축이라 별도 컬럼.
--
-- legacy/TOSS/STUB 행은 NULL — 애플리케이션이 NULL 이면 web 으로 폴백한다.
-- MySQL 은 ADD COLUMN IF NOT EXISTS 가 없어 information_schema 로 조건부 처리(V2·V10 과 동일 패턴).
-- 멱등 필수 — ECS churn 동시/재시도 실행 대비(#121).

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

-- WEB / APP (PaymentClient enum, @Enumerated(STRING))
CALL pd_add_col('payment_order', 'client', 'VARCHAR(8) NULL');

DROP PROCEDURE IF EXISTS pd_add_col;
