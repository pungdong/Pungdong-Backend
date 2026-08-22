-- ════════════════════════════════════════════════════════════════
-- 강의 저장(북마크) — 마커 테이블
--
-- 구조는 community_post_bookmark(V19) 와 같다: (대상, 계정) UNIQUE 로 멱등을 얻고, 상태 컬럼 없이
-- 행의 유무가 곧 상태다. 그래서 재시도·연타·동시 요청에도 카운트가 부풀지 않는다.
--
-- ix_course_bookmark_account = (account_id, created_at) 는 "저장한 강의" 목록이 계정 기준으로 읽히기
-- 때문이다. course_id 쪽은 UNIQUE 의 선두 컬럼이라 저장 수 집계가 그 인덱스를 그대로 쓴다.
--
-- 강의 삭제 시 ON DELETE CASCADE — 저장은 강의에 딸린 부수 정보라 남겨둘 이유가 없다. 계정 쪽은
-- CASCADE 를 걸지 않는다(탈퇴는 soft delete → 익명화 경로라 행을 물리 삭제하지 않는다).
--
-- 멱등: ECS 가 실패한 태스크를 빠르게 재시작해 같은 마이그레이션이 동시/재시도 실행될 수 있다.
-- 맨 CREATE TABLE 은 그때 1050(already exists)으로 *실패 기록* 이 남아 이후 부팅이 전부 막힌다(#121).
-- ════════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS `course_bookmark` (
  `id`         bigint   NOT NULL AUTO_INCREMENT,
  `course_id`  bigint   NOT NULL,
  `account_id` bigint   NOT NULL,
  `created_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_course_bookmark` (`course_id`, `account_id`),
  KEY `ix_course_bookmark_account` (`account_id`, `created_at`),
  CONSTRAINT `fk_course_bookmark_course`  FOREIGN KEY (`course_id`)  REFERENCES `course` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_course_bookmark_account` FOREIGN KEY (`account_id`) REFERENCES `account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
