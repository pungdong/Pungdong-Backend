-- V8 — identity_verification 을 SMS 휴대폰 본인인증(포트원 REST v2 / 다날) 2단계로 승격 (2026-07-07).
--   status                  : 레코드 생명주기 (READY/VERIFIED/FAILED). SMS 는 생성 시점부터 상태를 가진다.
--   method                  : 본인확인 방식 (SMS 실서비스 / APP 향후).
--   portone_verification_id : 우리가 발급한 포트원 identityVerificationId (발송/확인 매핑키, UNIQUE).
--   otp_expires_at          : OTP 유효기한.
--   attempt_count           : OTP 확인 시도 횟수 (초과 판정).
-- 또한 ci/di 는 이제 암호화(AES-GCM base64) 저장이라 컬럼 길이를 512 로 넓힌다.
-- carrier 는 V7 에서 이미 varchar — enum(SKT/KT/LGU/*_MVNO) 저장으로 의미만 바뀜(DDL 무변경).
--
-- 멱등(이미 있으면 no-op) — MySQL 은 ADD COLUMN IF NOT EXISTS 가 없어 information_schema 가드.
-- ECS churn 으로 같은 마이그레이션이 동시/재시도 실행돼도 1060(duplicate column)/1061(dup key)로
-- 실패하지 않게. (패턴은 V4/V7)

DROP PROCEDURE IF EXISTS pd_add_col;
DROP PROCEDURE IF EXISTS pd_add_unique;

DELIMITER //

CREATE PROCEDURE pd_add_col(IN tbl VARCHAR(64), IN col VARCHAR(64), IN ddl VARCHAR(255))
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col) THEN
    SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN ', ddl);
    PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END //

CREATE PROCEDURE pd_add_unique(IN tbl VARCHAR(64), IN idx VARCHAR(64), IN col VARCHAR(64))
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND INDEX_NAME = idx) THEN
    SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD UNIQUE INDEX `', idx, '` (`', col, '`)');
    PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END //

DELIMITER ;

CALL pd_add_col('identity_verification', 'status',
                '`status` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL');
CALL pd_add_col('identity_verification', 'method',
                '`method` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL');
CALL pd_add_col('identity_verification', 'portone_verification_id',
                '`portone_verification_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL');
CALL pd_add_col('identity_verification', 'otp_expires_at', '`otp_expires_at` datetime(6) DEFAULT NULL');
CALL pd_add_col('identity_verification', 'attempt_count', '`attempt_count` int NOT NULL DEFAULT 0');

-- ci/di: 암호문(base64) 수용 — MODIFY 는 재실행에 안전(멱등).
ALTER TABLE `identity_verification`
    MODIFY `ci` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
ALTER TABLE `identity_verification`
    MODIFY `di` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;

CALL pd_add_unique('identity_verification', 'uk_identity_verification_portone_id', 'portone_verification_id');

DROP PROCEDURE pd_add_col;
DROP PROCEDURE pd_add_unique;
