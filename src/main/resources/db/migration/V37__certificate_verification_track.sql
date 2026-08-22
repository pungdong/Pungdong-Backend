-- V37 — 강사 자격 검증 트랙 수렴 (2026-08-22).
--   1) student_certificate 에 검증 상태(verification_*) 추가, certificate_number/acquired_at 을 NULL 허용(백필 행만 null).
--   2) instructor_application_certificate: 신청 → 자격증 id 참조(@ElementCollection, 제출 순서).
--   3) certificate_review: 어드민 검수 큐(NEW/ADDITIONAL/RE_VERIFY 한 테이블).
--   4) 백필: application_certificate(옛 신청 첨부) → student_certificate 행 + 참조 + NEW 검수 행.
--      상태 매핑 APPROVED→VERIFIED / SUBMITTED→PENDING / REJECTED→REJECTED(사유 복사). 승인 건만 옮기면 심사 중인
--      신청의 첨부가 사라지므로 전 상태를 옮긴다. 사진 key 는 옛 prefix(instructorCertificate/) 그대로(같은 비공개 버킷).
--      사진(file_url) 이 없는 옛 첨부는 검증 근거가 없어 옮기지 않는다.
--   5) application_certificate DROP.
--
-- 멱등 — ADD COLUMN 은 information_schema 가드(패턴 V8), 백필은 원본 테이블 존재 + NOT EXISTS 가드로 재실행·동시실행에
-- 안전(ECS churn, #121). MODIFY / CREATE IF NOT EXISTS / DROP IF EXISTS 는 그 자체로 멱등.

DROP PROCEDURE IF EXISTS pd_add_col;
DROP PROCEDURE IF EXISTS pd_backfill_certificates;

DELIMITER //

CREATE PROCEDURE pd_add_col(IN tbl VARCHAR(64), IN col VARCHAR(64), IN ddl VARCHAR(255))
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col) THEN
    SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN ', ddl);
    PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END //

-- 원본(application_certificate)이 아직 있을 때만 — 동시 실행에서 한쪽이 먼저 DROP 해도 다른 쪽이 깨지지 않는다.
CREATE PROCEDURE pd_backfill_certificates()
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.TABLES
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'application_certificate') THEN

    INSERT INTO student_certificate
        (account_id, discipline_code, organization_code, organization_name, organization_full_name, level,
         certification_display_name, certificate_number, acquired_at, source, issuer, photo_file_key,
         created_at, verification_status, verification_kind, verification_reason,
         verification_requested_at, verification_reviewed_at)
    SELECT ia.account_id,
           ia.discipline_code,
           COALESCE(ac.organization_code, 'OTHER'),
           CASE WHEN UPPER(COALESCE(ac.organization_code, 'OTHER')) = 'OTHER'
                THEN ac.organization_other ELSE ac.organization_code END,
           CASE WHEN UPPER(COALESCE(ac.organization_code, 'OTHER')) = 'OTHER'
                THEN ac.organization_other ELSE NULL END,
           'INSTRUCTOR',
           NULL, NULL, NULL,
           'EXTERNAL', NULL,
           ac.file_url,
           COALESCE(ia.created_at, ia.submitted_at, NOW(6)),
           CASE ia.status WHEN 'APPROVED' THEN 'VERIFIED' WHEN 'SUBMITTED' THEN 'PENDING' ELSE 'REJECTED' END,
           'APPLICATION',
           CASE ia.status WHEN 'REJECTED' THEN ia.rejection_reason ELSE NULL END,
           ia.submitted_at,
           CASE ia.status WHEN 'SUBMITTED' THEN NULL ELSE ia.reviewed_at END
    FROM application_certificate ac
    JOIN instructor_application ia ON ia.id = ac.application_id
    WHERE ac.file_url IS NOT NULL
      AND ia.account_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM student_certificate sc WHERE sc.photo_file_key = ac.file_url);

    INSERT INTO instructor_application_certificate (application_id, certificate_id, sort_order)
    SELECT ia.id, sc.id, ac.sort_order
    FROM application_certificate ac
    JOIN instructor_application ia ON ia.id = ac.application_id
    JOIN student_certificate sc ON sc.photo_file_key = ac.file_url AND sc.account_id = ia.account_id
    WHERE ac.file_url IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM instructor_application_certificate x
                      WHERE x.application_id = ia.id AND x.certificate_id = sc.id);

  END IF;

  -- NEW 검수 행 — 신청마다 1건(자격증 불필요 종목 포함). 원본 테이블과 무관하게 멱등.
  INSERT INTO certificate_review
      (kind, application_id, certificate_id, account_id, discipline_code, status, reason,
       requested_at, reviewed_at, reviewer_id)
  SELECT 'NEW', ia.id, NULL, ia.account_id, ia.discipline_code,
         CASE ia.status WHEN 'APPROVED' THEN 'APPROVED' WHEN 'SUBMITTED' THEN 'PENDING' ELSE 'REJECTED' END,
         CASE ia.status WHEN 'REJECTED' THEN ia.rejection_reason ELSE NULL END,
         COALESCE(ia.submitted_at, ia.created_at, NOW(6)),
         CASE ia.status WHEN 'SUBMITTED' THEN NULL ELSE ia.reviewed_at END,
         ia.reviewer_id
  FROM instructor_application ia
  WHERE ia.account_id IS NOT NULL AND ia.discipline_code IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM certificate_review r WHERE r.application_id = ia.id);
END //

DELIMITER ;

-- 1) student_certificate 검증 컬럼 + nullable 완화
CALL pd_add_col('student_certificate', 'verification_status',
                '`verification_status` varchar(20) NOT NULL DEFAULT ''NONE''');
CALL pd_add_col('student_certificate', 'verification_kind', '`verification_kind` varchar(20) NULL');
CALL pd_add_col('student_certificate', 'verification_reason', '`verification_reason` longtext NULL');
CALL pd_add_col('student_certificate', 'verification_requested_at', '`verification_requested_at` datetime(6) NULL');
CALL pd_add_col('student_certificate', 'verification_reviewed_at', '`verification_reviewed_at` datetime(6) NULL');
ALTER TABLE `student_certificate` MODIFY `certificate_number` varchar(100) NULL;
ALTER TABLE `student_certificate` MODIFY `acquired_at` date NULL;

-- 2) 신청 → 자격증 참조
CREATE TABLE IF NOT EXISTS `instructor_application_certificate` (
  `application_id` bigint NOT NULL,
  `certificate_id` bigint NOT NULL,
  `sort_order`     int    NOT NULL,
  PRIMARY KEY (`application_id`, `sort_order`),
  KEY `idx_instructor_application_certificate_cert` (`certificate_id`),
  CONSTRAINT `fk_instructor_application_certificate_application`
    FOREIGN KEY (`application_id`) REFERENCES `instructor_application` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3) 검수 큐
CREATE TABLE IF NOT EXISTS `certificate_review` (
  `id`                          bigint       NOT NULL AUTO_INCREMENT,
  `kind`                        varchar(20)  NOT NULL,
  `application_id`              bigint       NULL,
  `certificate_id`              bigint       NULL,
  `account_id`                  bigint       NOT NULL,
  `discipline_code`             varchar(50)  NOT NULL,
  `status`                      varchar(20)  NOT NULL,
  `previous_discipline_code`    varchar(50)  NULL,
  `previous_organization_code`  varchar(50)  NULL,
  `previous_level`              varchar(30)  NULL,
  `previous_certificate_number` varchar(100) NULL,
  `reason`                      longtext     NULL,
  `requested_at`                datetime(6)  NOT NULL,
  `reviewed_at`                 datetime(6)  NULL,
  `reviewer_id`                 bigint       NULL,
  PRIMARY KEY (`id`),
  KEY `idx_certificate_review_status` (`status`, `requested_at`),
  KEY `idx_certificate_review_certificate` (`certificate_id`),
  KEY `idx_certificate_review_application` (`application_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4) 백필
CALL pd_backfill_certificates();

-- 5) 옛 첨부 테이블 제거
DROP TABLE IF EXISTS `application_certificate`;

DROP PROCEDURE pd_add_col;
DROP PROCEDURE pd_backfill_certificates;
