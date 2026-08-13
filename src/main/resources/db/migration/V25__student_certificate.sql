-- 학생 보유 자격증 (프로필 > 내 자격증).
-- 강사 신청의 application_certificate 와 별개 — 저쪽은 심사 자료, 이쪽은 본인 보유 기록.
--
-- 멱등: ECS 가 실패한 태스크를 빠르게 재시작해 같은 마이그레이션이 동시/재시도로 돌 수 있다.
-- 맨 CREATE TABLE 은 1050(already exists)으로 실패 기록되어 이후 모든 부팅을 막는다(2026-06-28 prod 사고).
CREATE TABLE IF NOT EXISTS student_certificate (
    id                         BIGINT       NOT NULL AUTO_INCREMENT,
    account_id                 BIGINT       NOT NULL,

    discipline_code            VARCHAR(50)  NOT NULL,
    organization_code          VARCHAR(50)  NOT NULL,
    -- 표시명은 등록 시점 Sanity 카탈로그 스냅샷(불변 credential). 없으면 FE 가 코드로 폴백.
    organization_name          VARCHAR(200) NULL,
    organization_full_name     VARCHAR(200) NULL,
    level                      VARCHAR(30)  NOT NULL,
    certification_display_name VARCHAR(200) NULL,

    certificate_number         VARCHAR(100) NOT NULL,
    acquired_at                DATE         NOT NULL,
    source                     VARCHAR(20)  NOT NULL,
    issuer                     VARCHAR(100) NULL,
    -- 비공개 버킷 객체 key(또는 dev 서빙 URL). 공개 URL 이 아니다.
    photo_file_key             VARCHAR(500) NULL,

    -- source=PUNGDONG 일 때만 채워지는 강의 스냅샷.
    enrollment_id              BIGINT       NULL,
    course_id                  BIGINT       NULL,
    course_title               VARCHAR(200) NULL,
    course_completed_at        DATE         NULL,
    instructor_name            VARCHAR(100) NULL,

    created_at                 DATETIME(6)  NOT NULL,

    PRIMARY KEY (id),
    KEY idx_student_certificate_owner (account_id),
    CONSTRAINT fk_student_certificate_account
        FOREIGN KEY (account_id) REFERENCES account (id)
-- ⚠️ collation 은 스키마 전체와 반드시 같아야 한다(utf8mb4_unicode_ci).
--    다르면 discipline_code → discipline.code 같은 조인이 "Illegal mix of collations" 로 죽고,
--    출시 후엔 커지는 PII 테이블에 ALTER … CONVERT TO 를 걸어야 한다.
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- enrollment_id 는 의도적으로 FK 를 걸지 않는다 — 연결한 수강이 나중에 정리돼도
-- 자격증(사용자 자산)은 남아야 한다. 값은 등록 시점 스냅샷의 출처 표시일 뿐이다.
