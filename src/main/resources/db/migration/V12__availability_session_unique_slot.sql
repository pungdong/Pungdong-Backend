-- V12 — availability_session 자연키(강사, 날짜, 시작·종료시간, 위치) UNIQUE 제약.
--
-- 왜: 선결제 전환으로 "오버부킹"이 "이중결제"가 된다 → 동시 신청 방지를 하드닝한다. requireSeat 의
-- 비관적 락(세션 행 SELECT ... FOR UPDATE)은 <b>같은 세션 행 위에서만</b> 직렬화한다 — 두 신청이 <b>같은 슬롯에
-- 각자 새 세션을 만드는 create 경합</b>은 락으론 못 막는다(서로 다른 행). 그걸 DB UNIQUE 로 차단한다(둘 중 하나만
-- insert 성공, 나머지는 제약 위반으로 실패 → 재시도 시 기존 세션을 찾음). 엔티티 @Table uk_availability_session_slot 와 일치.
--
-- MySQL 은 ADD ... IF NOT EXISTS(index) 가 없어 information_schema 로 조건부(V2/V10/V11 동일 패턴). 멱등(ECS churn 대비).
-- ⚠️ 기존 중복 행이 있으면 ADD 가 실패한다 — 배포 전 중복 없음 확인:
--    SELECT instructor_id,`date`,start_time,end_time,venue_ref_id,COUNT(*) c
--    FROM availability_session GROUP BY 1,2,3,4,5 HAVING c>1;
-- (find-or-create 로직상 중복은 사실상 없음. venue_ref_id NULL 은 UNIQUE 에서 다중 허용 = 위치없는 점유 제외.)

DROP PROCEDURE IF EXISTS pd_add_unique;

DELIMITER //

CREATE PROCEDURE pd_add_unique()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'availability_session'
                       AND INDEX_NAME = 'uk_availability_session_slot') THEN
    ALTER TABLE `availability_session`
      ADD CONSTRAINT `uk_availability_session_slot`
      UNIQUE (`instructor_id`, `date`, `start_time`, `end_time`, `venue_ref_id`);
  END IF;
END //

DELIMITER ;

CALL pd_add_unique();

DROP PROCEDURE IF EXISTS pd_add_unique;
