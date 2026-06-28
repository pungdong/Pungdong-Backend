-- V3 — 강사 일정변경 제안(propose-slots) 좌석 보장 hold(2026-06-28).
-- availability_hold 를 재사용해 제안 보장 hold 를 담는다(별도 테이블 대신 — heldCount 가 모든 hold 를 합산하므로
-- 제안 hold 가 만석 판정/캘린더 잔여에 자동 반영돼 하드캡이 한 곳에서만 계산됨). 두 nullable 컬럼 추가:
--   proposal_round_id — 제안 hold 가 귀속된 회차(EnrollmentRound) id. null 이면 외부/± hold. raw id(역참조 회피).
--   expires_at        — 제안 hold 자동 만료 시각(proposalTtlHours, 기본 6h). 학생 미선택 시 sweep 으로 해제.
--
-- 운영은 hbm2ddl=validate 라 엔티티(AvailabilityHold)와 정확히 일치해야 부팅된다. 새 컬럼이라 환경 간 drift 없어
-- 단순 ADD COLUMN(테스트는 H2 create-drop 으로 flyway 미실행 — 이 SQL 은 운영/스테이징 전용, 테스트 검증 불가).

ALTER TABLE `availability_hold`
  ADD COLUMN `proposal_round_id` bigint DEFAULT NULL,
  ADD COLUMN `expires_at` datetime DEFAULT NULL;
