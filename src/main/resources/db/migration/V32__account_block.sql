-- V32 — 유저 차단(account_block).
--
-- 애플 App Store 심사 가이드라인 1.2(UGC)는 "신고 수단" 과 "학대적 사용자를 차단하는 수단" 을 함께
-- 요구한다. 신고는 V19 에서 들어왔고(content_report), 차단은 레포 전체에 인프라가 없었다.
--
-- ## 왜 별도 테이블인가 (account 컬럼이 아니라)
--
-- 차단은 계정의 속성이 아니라 계정 쌍(pair)의 관계다. N:M 이라 컬럼으로 표현할 수 없다.
--
-- ## UNIQUE 가 멱등성의 근거다
--
-- (blocker, blocked) UNIQUE 덕에 POST 를 두 번 보내도 1건이다 — 좋아요·북마크·신고와 같은 규칙이고,
-- 중복 차단은 에러가 아니라 200 이다(사용자 입장에선 "차단됨" 이 맞는 결과다).
--
-- ## 인덱스가 두 방향인 이유
--
-- 차단은 상호 은닉이다 — 내가 A 를 차단하면 A 도 내 글을 못 본다. 그래서 필터 술어가
-- "(blocker=뷰어 and blocked=작성자) or (blocked=뷰어 and blocker=작성자)" 로 양방향이다.
-- UNIQUE(blocker, blocked) 가 앞 절을, ix_account_block_reverse(blocked, blocker) 가 뒤 절을 받는다.
-- 뒤 인덱스가 없으면 피드 한 페이지마다 account_block 풀스캔이 붙는다.
--
-- ## ON DELETE CASCADE
--
-- 탈퇴(익명화)로 계정 행이 지워지는 경로는 없지만(soft delete + PII 익명화), 관리자 정리나 시드
-- 재생성에서 계정을 지우면 차단 행이 고아로 남는다. 정리 책임은 DB 에 둔다(커뮤니티 자식 행과 같은 기조).
--
-- 멱등: CREATE TABLE IF NOT EXISTS. ECS 롤링 배포 중 같은 마이그레이션이 동시/재시도 실행돼도
-- 1050(table already exists)으로 실패해 이후 부팅이 전부 막히는 일이 없어야 한다(2026-06-28 사고, #121).
CREATE TABLE IF NOT EXISTS `account_block` (
  `id`                 bigint   NOT NULL AUTO_INCREMENT,
  `blocker_account_id` bigint   NOT NULL,
  `blocked_account_id` bigint   NOT NULL,
  `created_at`         datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_account_block_once` (`blocker_account_id`, `blocked_account_id`),
  KEY `ix_account_block_reverse` (`blocked_account_id`, `blocker_account_id`),
  CONSTRAINT `fk_account_block_blocker` FOREIGN KEY (`blocker_account_id`) REFERENCES `account` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_account_block_blocked` FOREIGN KEY (`blocked_account_id`) REFERENCES `account` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
