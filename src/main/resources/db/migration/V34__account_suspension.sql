-- V34 — 계정 정지(account.suspended_at).
--
-- ## 왜 필요한가
--
-- 신고 대상에 USER 를 더하면서 생긴 컬럼이다. 이 레포는 "조치(ACTIONED) = 실제로 무언가 일어난다" 를
-- 불변식으로 지킨다 — 상태만 바뀌고 아무 일도 없으면 어드민이 "처리했다" 고 믿는데 신고당한 사람이
-- 그대로 활동하는, 가장 나쁜 종류의 어긋남이 생긴다. 글·댓글·강의·메시지는 각각 숨길 대상이 있지만
-- 사용자 신고의 조치 대상은 계정 자체다.
--
-- ## 왜 is_deleted 를 재사용하지 않았나
--
-- 탈퇴(soft delete)와 정지는 성격이 다르다. 탈퇴는 본인이 하고 30일 뒤 PII 를 익명화하는 되돌릴 수
-- 없는 절차이고, 정지는 어드민이 하고 해제할 수 있어야 한다. 한 컬럼에 얹으면 "탈퇴한 사람" 과
-- "정지된 사람" 을 구분할 수 없어 익명화 배치가 정지 계정까지 파기한다.
--
-- ## 효과의 범위
--
-- 로그인·토큰 갱신을 막고(= 접근 차단), 이미 발급된 토큰도 다음 요청에서 걸린다. 기존 콘텐츠는
-- 지우지 않는다 — 개별 콘텐츠는 개별 신고로 조치하는 게 이 도메인의 규칙이고, 정지가 글을 쓸어버리면
-- 남의 스레드가 함께 끊긴다.
--
-- 멱등: information_schema 로 존재 확인 후 추가(V2·V19·V33 과 같은 패턴). ECS 롤링/재시도로 같은
-- 마이그레이션이 동시·반복 실행돼도 1060(duplicate column)으로 실패하지 않는다.

DROP PROCEDURE IF EXISTS pd_v34_add_col;

DELIMITER //

CREATE PROCEDURE pd_v34_add_col(IN tbl VARCHAR(64), IN col VARCHAR(64), IN ddl TEXT)
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col) THEN
    SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', ddl);
    PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END //

DELIMITER ;

CALL pd_v34_add_col('account', 'suspended_at', 'datetime DEFAULT NULL');

DROP PROCEDURE IF EXISTS pd_v34_add_col;
