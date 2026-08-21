-- V35 — 예약가능시간(coverage) 의 "하루 끝" 을 canonical 23:59:59 로 수렴(기존 행 백필).
--
-- 하루 끝의 정식 표현은 23:59:59 다(LocalTime 은 24:00 을 표현 못 해 FE 가 타임라인/위치 피커의 24:00 을
-- 23:59:59 로 번역해 보낸다 — availability/DayEnd, docs/features/instructor-availability.md). 코드는 이제
-- 23:59 이상으로 끝나는 입력을 전부 23:59:59 로 정규화해 저장하지만, **이미 저장된 행은 코드가 못 고친다**:
-- 데모 시더(SeededCourseAvailabilitySeeder)가 23:59:00 으로 만든 coverage 가 staging/prod 에 남아 있고,
-- 시더는 "coverage 없는 날만" 채우므로 재부팅해도 덮어쓰지 않는다.
--
-- 왜 지금 문제가 되나: 수강신청 슬롯은 venue 블록이 coverage 에 통째로 들어갈 때만 생긴다
-- (CoverageMerger.containsWhole, block.end <= cov.end). FE #694 이후 커스텀 위치 블록 끝이 23:59:59 로 저장될
-- 수 있는데, 시더 coverage 가 23:59:00 이면 23:59:59 > 23:59:00 이라 그 블록이 데모 데이터에서 조용히 탈락한다.
--
-- 범위: availability_coverage 만. availability_session 의 end_time 은 (date,위치,start,end) 가 일정의 자연키라
-- 회차/hold 가 그 시각으로 묶여 있어 여기서 건드리지 않는다(23:59:00 session 은 FE 가 만들 수 없었으므로
-- 실데이터도 없다). 한 (instructor,date) 의 coverage 는 머지돼 비겹침이라 끝만 59초 늘려도 충돌이 없다.
--
-- 멱등: 조건에 맞는 행만 갱신하고, 두 번째 실행은 0행 매치 — ECS 롤링/재시도 동시 실행에 안전.

UPDATE availability_coverage
   SET end_time = '23:59:59'
 WHERE end_time >= '23:59:00'
   AND end_time <  '23:59:59';
