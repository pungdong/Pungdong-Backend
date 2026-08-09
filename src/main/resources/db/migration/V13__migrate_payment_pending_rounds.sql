-- V13 — 전 회차 선결제 통일(2026-08-09): 사라진 `PAYMENT_PENDING` 상태의 잔존 행을 새 모델로 이관.
--
-- 왜 필수인가: enrollment_round.status 는 @Enumerated(STRING) → varchar 라 DDL 제약은 없지만,
-- 자바 enum 에서 값이 없어졌으므로 남은 행을 <b>읽는 순간</b> Enum.valueOf 가 터진다(IllegalArgumentException).
-- 스키마가 아니라 데이터를 고쳐야 하는 마이그레이션.
--
-- 이관 규칙: PAYMENT_PENDING = "강사가 사전수락했고 학생은 아직 미결제" → 새 모델의 미결제 `PENDING` 과 등가
-- (강사 결정은 이제 결제 <b>후</b>에 하므로 사전수락 사실은 버린다 — 학생이 결제하면 강사가 다시 확인한다).
--
-- created_at 을 지금으로 리셋하는 이유: 새 모델에서 PENDING 의 만료 시계는 created_at + paymentTtlHours(12h) 다.
-- 옛 created_at(며칠 전 신청) 그대로면 배포 직후 첫 스위프에 즉시 만료돼, 결제창을 띄우려던 학생의 좌석이
-- 예고 없이 풀린다. 이관된 건에는 12h 결제창을 새로 준다.
--
-- 멱등: WHERE 로 대상이 없으면 0행 UPDATE(no-op). 재실행·동시실행 안전.

UPDATE enrollment_round
   SET status = 'PENDING',
       created_at = UTC_TIMESTAMP()
 WHERE status = 'PAYMENT_PENDING';
