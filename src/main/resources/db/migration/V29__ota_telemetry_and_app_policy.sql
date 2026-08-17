-- V29 — OTA 텔레메트리(ota_device) + 앱 정책(app_policy).
--
-- 왜 firebase_token 확장이 아니라 새 테이블인가 (사용자 결정 D1, 2026-08-17):
-- firebase_token 은 "푸시 토큰"의 수명을 산다 — 로그아웃 시 삭제(deleteByAccountAndToken),
-- 탈퇴 시 하드삭제(deleteByAccount_Id), 푸시 권한 거부 기기는 애초에 행이 없다. 거기에 얹으면
-- "잘못된 번들이 나갔을 때 몇 명이 어디 있나" 가 구조적으로 과소집계되고 그 어긋남이 조용하다.
-- 특히 "잘못된 번들에 갇힌 사용자" 는 앱이 이상해서 로그아웃했을 가능성이 높아, 가장 보고 싶은 집단이
-- 우선적으로 지워진다. 그래서 앱이 만든 installId 를 키로 하는 독립 테이블을 둔다.
--
-- 멱등 필수: ECS 가 실패 task 를 빠르게 재시작해 같은 마이그레이션이 동시/재시도 실행될 수 있고,
-- 그때 bare CREATE TABLE 은 1050(table already exists)로 '실패 기록' 되어 이후 모든 부팅을 막는다(#121).
--
-- ⚠️ VARCHAR 고정: CHAR(n) 으로 두면 Flyway 는 통과하지만 hbm2ddl=validate 가
--    "found [char], but expecting [varchar(n)]" 로 부팅을 거부한다(V23 에서 실제로 밟았다).
--    테스트는 H2 + Flyway OFF 라 이 불일치를 못 잡는다 — 빈 MySQL 부팅으로만 확인된다.
-- ⚠️ DATETIME(6): 초 단위로 잘리면 같은 초에 갱신된 행들의 순서가 불확정이 되어
--    last_seen_at DESC 페이지네이션이 행을 건너뛰거나 중복시킨다. 정렬엔 id DESC 타이브레이커도 함께 건다.

CREATE TABLE IF NOT EXISTS ota_device (
    id                              BIGINT       NOT NULL AUTO_INCREMENT,
    -- 앱이 최초 실행에 생성해 AsyncStorage 에 영속하는 불투명 설치 식별자(재설치하면 새 값).
    -- ★ UUID 형식을 강제하지 않는다: RN(Hermes)엔 WebCrypto 가 없어 앱이 만드는 값은 암호학적 난수가
    --   아니고, 형식을 못 박으면 앱이 생성 방식을 바꾸는 순간 그 기기가 조용히 영구 이탈한다.
    --   BE 는 이 값을 파싱하지 않는다 — 길이/문자셋만 본다.
    -- 🔒 암호학적 난수가 아니므로 인증 수단이 아니다. 이 값을 키로 하는 '비인증 읽기' 경로를 만들지 말 것.
    install_id                      VARCHAR(64)  NOT NULL,
    -- FK 없음 — 알림 outbox 와 같은 기조(집계 도메인이 account 에 강결합되지 않게).
    -- 탈퇴 익명화 시 NULL 로 끊고 행은 남긴다(기기 통계는 PII 가 아니다).
    account_id                      BIGINT       NULL,
    -- ★ NULL 허용: 이벤트가 부팅 upsert 보다 먼저 도착하면 platform 을 모르는 최소 행이 먼저 생긴다.
    --   다음 부팅 upsert 가 채운다. 요청 바디에서는 required 다.
    platform                        VARCHAR(16)  NULL,
    -- ↓ 라이브러리 getter 가 실제로 nullable 이고 네이티브 모듈이 없으면 throw 도 한다.
    --   NOT NULL 로 잡고 400 을 되돌리면 그 기기는 영영 집계 밖이 된다(그리고 앱은 400 을 조용히 삼킨다).
    app_version                     VARCHAR(20)  NULL,
    ota_channel                     VARCHAR(16)  NULL,
    -- 실측 40자 hex(SHA-1). 알고리즘이 라이브러리 소관이라 여유 64 + 형식 미검증.
    fingerprint_hash                VARCHAR(64)  NULL,
    -- 내장 번들이면 NULL. ota_min_bundle_id 와 같으면 서버가 NULL 로 정규화한다(§내장 방어).
    ota_bundle_id                   VARCHAR(36)  NULL,
    ota_min_bundle_id               VARCHAR(36)  NULL,
    -- "1".."1000" 숫자 문자열 또는 커스텀 슬러그. BE 는 집계·필터에 쓰지 않고 저장·표시만 한다.
    -- 어드민이 rollout 인하 회수 대상 계산 + "커스텀 코호트라 rollout 대상이 아닌 기기" 탐지에 쓴다.
    ota_cohort                      VARCHAR(64)  NULL,
    -- JSON array of bundle id (Jackson 직렬화). 네이티브 MySQL JSON 타입은 이 레포에 전례가 없다
    -- (user_notification.data 와 같은 TEXT + Jackson).
    crash_history                   TEXT         NULL,
    downloaded_bundle_id            VARCHAR(36)  NULL,
    downloaded_at                   DATETIME(6)  NULL,
    server_rollback_from_bundle_id  VARCHAR(36)  NULL,
    server_rollback_at              DATETIME(6)  NULL,
    crash_rollback_bundle_id        VARCHAR(36)  NULL,
    -- ★ 이름 주의: 크래시 시각이 아니라 '보고 시각'이다. 크래시는 이전 실행에서 났고 네이티브가 롤백한 뒤
    --   다음 부팅에 보고하므로, 사용자가 며칠 뒤 앱을 켜면 그만큼 늦게 찍힌다. 실제 크래시 시각은 관측 불가.
    crash_rollback_reported_at      DATETIME(6)  NULL,
    last_seen_at                    DATETIME(6)  NOT NULL,
    created_at                      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ota_device_install_id (install_id),
    KEY idx_ota_device_bundle     (ota_channel, ota_bundle_id, last_seen_at),
    KEY idx_ota_device_downloaded (downloaded_bundle_id),
    KEY idx_ota_device_srollback  (server_rollback_from_bundle_id),
    KEY idx_ota_device_crollback  (crash_rollback_bundle_id),
    KEY idx_ota_device_seen       (ota_channel, last_seen_at),
    KEY idx_ota_device_account    (account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 앱 최소버전 게이트 정책 — 항상 1행.
--
-- id 를 AUTO_INCREMENT 로 두지 않는 이유: "정확히 1행"을 PK 고정으로 구조적으로 보장한다(항상 id=1).
-- 애플리케이션 레벨 unique 보다 싸고, 두 행이 생겨 어느 쪽이 진짜인지 모르는 상태가 원천 봉쇄된다.
--
-- 시드 INSERT 를 넣지 않는 이유: 행이 없을 때의 폴백(minVersion "0.0.0" = 전 버전 통과)이 곧 안전
-- 기본값이다. 시드를 넣으면 "행이 없는 경로"가 프로덕션에서 한 번도 안 돌아 테스트만 통과한 죽은 코드가 된다.
--
-- ⚠️ fail-safe 방향이 site_settings 와 반대다: 런칭 게이트는 "사고 시 잠그는" 쪽이 안전하지만,
--    앱 정책은 "사고 시 여는" 쪽이 안전하다 — 게이트가 사용자를 앱 밖에 가두면 안 된다.
CREATE TABLE IF NOT EXISTS app_policy (
    id                     BIGINT       NOT NULL,
    ios_min_version        VARCHAR(20)  NOT NULL,
    ios_latest_version     VARCHAR(20)  NULL,
    ios_store_url          VARCHAR(500) NULL,
    android_min_version    VARCHAR(20)  NOT NULL,
    android_latest_version VARCHAR(20)  NULL,
    android_store_url      VARCHAR(500) NULL,
    message                VARCHAR(500) NULL,
    updated_by_account_id  BIGINT       NULL,
    updated_at             DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
