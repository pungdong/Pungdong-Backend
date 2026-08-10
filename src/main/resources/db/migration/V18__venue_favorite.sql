-- V18 — 강사별 위치 즐겨찾기 표식 테이블.
--
-- 왜: 코스빌더 위치 picker 의 "내 위치" 묶음이 지금은 FE 로컬 스토리지라 기기를 바꾸면 사라진다.
-- 강사가 "자주 쓰는 위치"라고 선언한 의도는 서버에 남아야 한다. 위치는 venueRefId
-- ("CUSTOM:<pk>" | "OFFICIAL:<sanityId>") 로 가리켜 공식·커스텀을 한 테이블이 함께 담는다.
--
-- 왜 venue_equipment_extension((owner, venue_ref_id) 유니크가 이미 있음)에 컬럼으로 안 붙였나:
-- 장비 가격표는 코스 읽기에서 입장료·대여료를 합성하는 사업 데이터고 즐겨찾기는 UI 선호다.
-- 섞으면 GET /venue-equipment 가 items 0개짜리 껍데기 행을 뱉고, 두 기능의 수명주기가 엉킨다.
--
-- CREATE TABLE IF NOT EXISTS 는 그 자체로 멱등 — ECS churn 동시/재시도 실행 대비(#121).
-- 컬럼 타입·charset·FK 모양은 V1__baseline 의 venue_equipment_extension 을 그대로 따른다.

CREATE TABLE IF NOT EXISTS `venue_favorite` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime DEFAULT NULL,
  `venue_ref_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `owner_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_favorite_owner_venue_ref` (`owner_id`,`venue_ref_id`),
  CONSTRAINT `fk_venue_favorite_owner` FOREIGN KEY (`owner_id`) REFERENCES `account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
