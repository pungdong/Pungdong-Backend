-- V27 — v1 레거시 테이블 + 고아 테이블/컬럼 드롭 (2026-08-15)
--
-- 배경: v1 layered 스택(com.diving.pungdong.{controller,service,repo,domain,dto})을 코드에서
-- 삭제했다(별도 PR). 그 엔티티들이 쓰던 테이블 16개가 DB 에만 남아 있고, 어떤 엔티티도 매핑하지
-- 않는 고아 테이블 6개 + 고아 컬럼 1개도 함께 정리한다.
--
-- ⚠️ 이 마이그레이션은 **되돌릴 수 없다**(DROP TABLE). 머지 전에 각 환경에서 row count 를 확인하는
--    게이트를 통과할 것 — 확인 쿼리는 이 PR 본문 / scratchpad ROWCOUNT.sql 참고.
--
-- 멱등성(루트 CLAUDE.md "Migrations MUST be idempotent"): ECS 가 실패한 태스크를 빨리 재시작해
-- 같은 마이그레이션이 동시/재시도 실행될 수 있다. DROP TABLE IF EXISTS 는 그 자체로 멱등이고,
-- 컬럼·FK 는 MySQL 에 IF EXISTS 가 없어 information_schema + stored procedure 로 조건부 처리한다(V2 패턴).
--
-- 드롭 순서 = 자식 → 부모 (실 스키마의 FK 를 information_schema 로 실측해 정렬).
--   reservation.review_id  -> review        (reservation 을 review 보다 먼저)
--   reservation.payment_id -> payment       (reservation 을 payment 보다 먼저)
--   equipment.lecture_id   -> lecture
--   lecture.location_id    -> location      (lecture 를 location 보다 먼저)


-- ─────────────────────────────────────────────────────────────────────────────
-- 0. 조건부 DDL 헬퍼 (V2 와 동일 패턴)
-- ─────────────────────────────────────────────────────────────────────────────
DROP PROCEDURE IF EXISTS pd_v27_drop_col;
DROP PROCEDURE IF EXISTS pd_v27_drop_fk_on_col;

DELIMITER //

CREATE PROCEDURE pd_v27_drop_col(IN tbl VARCHAR(64), IN col VARCHAR(64))
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col) THEN
    SET @sql = CONCAT('ALTER TABLE `', tbl, '` DROP COLUMN `', col, '`');
    PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END //

-- FK 이름은 Hibernate 가 결정적으로 만들어 환경마다 같지만(여기선 FK4fddcdb5hpgjbpb3grio35y49),
-- 이름에 의존하지 않고 (테이블, 컬럼) 으로 찾아 드롭한다.
CREATE PROCEDURE pd_v27_drop_fk_on_col(IN tbl VARCHAR(64), IN col VARCHAR(64))
BEGIN
  DECLARE fkname VARCHAR(64);
  SELECT CONSTRAINT_NAME INTO fkname FROM information_schema.KEY_COLUMN_USAGE
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
          AND REFERENCED_TABLE_NAME IS NOT NULL
    LIMIT 1;
  IF fkname IS NOT NULL THEN
    SET @sql = CONCAT('ALTER TABLE `', tbl, '` DROP FOREIGN KEY `', fkname, '`');
    PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END //

DELIMITER ;


-- ─────────────────────────────────────────────────────────────────────────────
-- 1. 고아 컬럼: venue_equipment_item.profile_id (+ FK4fddcdb5hpgjbpb3grio35y49)
--
--    venue_equipment_profile 을 드롭하려면 이 FK 를 먼저 끊어야 한다. VenueEquipmentItem
--    엔티티에는 profile 매핑이 없다(사이즈 컬렉션 venue_equipment_item_size 만 있음) —
--    hbm2ddl=validate 는 엔티티→DB 방향만 보므로 이 잉여 컬럼을 잡아주지 못한다.
-- ─────────────────────────────────────────────────────────────────────────────
CALL pd_v27_drop_fk_on_col('venue_equipment_item', 'profile_id');
CALL pd_v27_drop_col('venue_equipment_item', 'profile_id');


-- ─────────────────────────────────────────────────────────────────────────────
-- 2. v1 레거시 테이블 15개 (payment 제외 — 아래 §4 별도 블록)
--    자식 → 부모 순서. 순서를 바꾸면 FK 위반으로 실패한다.
-- ─────────────────────────────────────────────────────────────────────────────

-- 예약(reservation) 계열 — review·payment·schedule 을 참조하므로 가장 먼저
DROP TABLE IF EXISTS reservation_equipment;
DROP TABLE IF EXISTS reservation;

-- 후기(review) 계열
DROP TABLE IF EXISTS review_image;
DROP TABLE IF EXISTS review;

-- 일정(schedule) 계열
DROP TABLE IF EXISTS schedule_equipment_stock;
DROP TABLE IF EXISTS schedule_equipment;
DROP TABLE IF EXISTS schedule_date_time;
DROP TABLE IF EXISTS schedule;

-- 장비(equipment) 계열 — lecture 를 참조
DROP TABLE IF EXISTS equipment_stock;
DROP TABLE IF EXISTS equipment;

-- 강의(lecture) 계열
DROP TABLE IF EXISTS lecture_image;
DROP TABLE IF EXISTS lecture_mark;
DROP TABLE IF EXISTS lecture_service_tags;
DROP TABLE IF EXISTS lecture;

-- 위치(location) — lecture 가 참조했으므로 lecture 다음
DROP TABLE IF EXISTS location;


-- ─────────────────────────────────────────────────────────────────────────────
-- 3. 고아 테이블 6개 — 어떤 라이브 엔티티도 매핑하지 않음(코드 grep 0)
--      enrollment_equipment            → enrollment_round_equipment 로 대체됨
--      enrollment_round_proposed_date  → enrollment_round_proposed_slot 로 대체됨
--      venue_closure_nth               → VenueClosure 는 venue_closure_weekday 만 씀
--      venue_equipment_profile         → 매핑 엔티티 없음 (§1 에서 FK 를 먼저 끊었다)
--      venue_media                     → 매핑 엔티티 없음
--      hibernate_sequence              → 전 엔티티가 GenerationType.IDENTITY, 시퀀스 미사용
-- ─────────────────────────────────────────────────────────────────────────────
DROP TABLE IF EXISTS enrollment_equipment;
DROP TABLE IF EXISTS enrollment_round_proposed_date;
DROP TABLE IF EXISTS venue_closure_nth;
DROP TABLE IF EXISTS venue_equipment_profile;
DROP TABLE IF EXISTS venue_media;
DROP TABLE IF EXISTS hibernate_sequence;


-- ═════════════════════════════════════════════════════════════════════════════
-- 4. ▼▼▼ 레거시 payment 테이블 — 별도 토글 블록 ▼▼▼
--
--    ⚠️ row 가 있으면 **이 블록만 통째로 지우고** 나머지는 그대로 배포할 것.
--       (v1 결제 기록은 전자상거래법상 보존 대상일 수 있다. 이 표는 v2 결제와 무관 —
--        v2 는 payment_order / payment_approval / refund_order / payment_callback_log 를 쓴다.)
--    이 블록을 지워도 §2 는 영향받지 않는다: payment 를 참조하던 reservation 이 §2 에서 이미
--    드롭됐으므로 payment 는 어느 쪽이든 FK 고아 상태로 안전하게 남거나 사라진다.
--
--    확인: SELECT COUNT(*) FROM payment;
-- ─────────────────────────────────────────────────────────────────────────────
DROP TABLE IF EXISTS payment;
-- ▲▲▲ 레거시 payment 블록 끝 ▲▲▲
-- ═════════════════════════════════════════════════════════════════════════════


-- ─────────────────────────────────────────────────────────────────────────────
-- 5. 헬퍼 정리
-- ─────────────────────────────────────────────────────────────────────────────
DROP PROCEDURE pd_v27_drop_col;
DROP PROCEDURE pd_v27_drop_fk_on_col;
