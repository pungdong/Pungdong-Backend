-- V30 — branding_post.caption 을 varchar(2000) → varchar(5000) 으로 넓힌다.
--
-- 왜: 커뮤니티 글 작성/수정 DTO(CommunityPostRequest.body)는 @Size(max = 5000) 이고 공개 계약
-- (docs/api-clients/types.ts)에도 5000 으로 나가 있는데, 컬럼은 V17 이 만든 varchar(2000) 그대로였다.
-- 즉 2001~5000자 본문은 **Bean Validation 을 통과하고 INSERT/UPDATE 에서 터진다**(500).
-- hbm2ddl=validate 는 컬럼 '길이' 를 안 보기 때문에 부팅도 성공했고, 테스트는 H2 를 엔티티에서
-- 생성하므로 긴 본문 시나리오가 없는 한 초록이었다 — 도메인 CLAUDE.md 가 경고한 그 함정의 실례다.
--
-- 방향: 계약(5000)을 낮추는 대신 컬럼을 넓힌다. 낮추면 (a) 이미 배포된 공개 계약이 후퇴하고
-- (b) 이미 2000자를 넘겨 저장된 글이 있을 경우 그 글들이 '열어서 저장만 눌러도 400' 인 수정 불가
-- 상태가 된다. 넓히는 쪽은 데이터 손실이 없다.
--
-- 브랜딩 작성 경로(BrandingPostRequest.caption)는 자체 @Size(max = 2000) 을 유지한다 — 컬럼이
-- 넓어져도 그쪽 계약은 그대로다. 컬럼은 두 경로 중 더 넓은 쪽을 수용하면 된다.
--
-- utf8mb4 기준 5000자 = 최대 20000 bytes 로 InnoDB 행 크기 한계에 걸리지 않는다(이 컬럼엔 인덱스 없음).
--
-- 멱등성: MODIFY COLUMN 은 재실행해도 에러가 아니라 no-op 이지만, 이미 넓혀진 DB 에서 불필요한
-- 테이블 재작성이 일어나지 않도록 information_schema 로 현재 길이를 확인하고 넘어간다.
-- (ECS 롤링/재시도로 같은 마이그레이션이 동시·반복 실행될 수 있다 — V2 와 같은 패턴.)

DROP PROCEDURE IF EXISTS pd_widen_col;

DELIMITER //

CREATE PROCEDURE pd_widen_col(IN tbl VARCHAR(64), IN col VARCHAR(64), IN target INT)
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
                   AND CHARACTER_MAXIMUM_LENGTH < target) THEN
    SET @sql = CONCAT('ALTER TABLE `', tbl, '` MODIFY COLUMN `', col, '` varchar(', target, ')');
    PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END //

DELIMITER ;

CALL pd_widen_col('branding_post', 'caption', 5000);

DROP PROCEDURE IF EXISTS pd_widen_col;
