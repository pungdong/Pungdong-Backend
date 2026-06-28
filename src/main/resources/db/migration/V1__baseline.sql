-- V1 baseline — 2026-06-28 현재(정리된) 로컬 스키마 덤프. 기존 DB 는 baseline-on-migrate 로 '이미 적용' 표시만(안 돌림),
-- 새 DB(staging/prod 신규·CI)만 이걸로 생성. 이후 구조 변경은 V2,V3… 로.
-- forward FK 참조 때문에 생성 동안 FK 체크 끔(생성 후 복원).

SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE IF NOT EXISTS `account` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `birth` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `email` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `gender` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `income` bigint DEFAULT NULL,
  `is_certified` bit(1) DEFAULT NULL,
  `is_deleted` bit(1) DEFAULT NULL,
  `is_request_certified` bit(1) DEFAULT NULL,
  `nick_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `organization` int DEFAULT NULL,
  `password` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `phone_number` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `self_introduction` longtext COLLATE utf8mb4_unicode_ci,
  `profile_photo_id` bigint DEFAULT NULL,
  `provider` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `social_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `default_capacity` int DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKcyib4xu8sysggfhcocij8irfd` (`profile_photo_id`),
  CONSTRAINT `FKcyib4xu8sysggfhcocij8irfd` FOREIGN KEY (`profile_photo_id`) REFERENCES `profile_photo` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `account_roles` (
  `account_id` bigint NOT NULL,
  `roles` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  KEY `FKtp61eta5i06bug3w1qr6286uf` (`account_id`),
  CONSTRAINT `FKtp61eta5i06bug3w1qr6286uf` FOREIGN KEY (`account_id`) REFERENCES `account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `agreement_term_archive` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `archived_at` datetime DEFAULT NULL,
  `body` longtext COLLATE utf8mb4_unicode_ci,
  `required` bit(1) NOT NULL,
  `term_key` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `title` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `version` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agreement_term_key_version` (`term_key`,`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `application_certificate` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `fileurl` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `sort_order` int NOT NULL,
  `application_id` bigint DEFAULT NULL,
  `organization_code` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `organization_other` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKfwtep9lcqie15oeubqnyljog0` (`application_id`),
  CONSTRAINT `FKfwtep9lcqie15oeubqnyljog0` FOREIGN KEY (`application_id`) REFERENCES `instructor_application` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `availability_coverage` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `date` date DEFAULT NULL,
  `end_time` time DEFAULT NULL,
  `start_time` time DEFAULT NULL,
  `instructor_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKelf7tpjtq9xmu0jtga6jqb4dg` (`instructor_id`),
  CONSTRAINT `FKelf7tpjtq9xmu0jtga6jqb4dg` FOREIGN KEY (`instructor_id`) REFERENCES `account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `availability_hold` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `count` int NOT NULL,
  `created_at` datetime DEFAULT NULL,
  `memo` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `session_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKj6hp598edi6k90gjnn7p2q6pt` (`session_id`),
  CONSTRAINT `FKj6hp598edi6k90gjnn7p2q6pt` FOREIGN KEY (`session_id`) REFERENCES `availability_session` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `availability_session` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `capacity_override` int DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  `date` date DEFAULT NULL,
  `end_time` time DEFAULT NULL,
  `start_time` time DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `venue_ref_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `instructor_id` bigint DEFAULT NULL,
  `ticket_ref` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK3lf0i8p9mlxuomtmd9mx6u3aw` (`instructor_id`),
  CONSTRAINT `FK3lf0i8p9mlxuomtmd9mx6u3aw` FOREIGN KEY (`instructor_id`) REFERENCES `account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `consent` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `agreed_at` datetime DEFAULT NULL,
  `context` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `account_id` bigint NOT NULL,
  `agreement_term_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK8oq7fur57qtjf5jlbeignpw90` (`account_id`),
  KEY `FK7k3xyxolhexshbqjktwmpgoow` (`agreement_term_id`),
  CONSTRAINT `FK7k3xyxolhexshbqjktwmpgoow` FOREIGN KEY (`agreement_term_id`) REFERENCES `agreement_term_archive` (`id`),
  CONSTRAINT `FK8oq7fur57qtjf5jlbeignpw90` FOREIGN KEY (`account_id`) REFERENCES `account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `course` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime DEFAULT NULL,
  `description` longtext COLLATE utf8mb4_unicode_ci,
  `discipline_code` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `kind` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `organization_code` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `price` int NOT NULL,
  `status` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `title` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `total_rounds` int NOT NULL,
  `updated_at` datetime DEFAULT NULL,
  `instructor_id` bigint DEFAULT NULL,
  `primary_location_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `seeded` bit(1) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK3ns8ghd3m06t8uxhbho1gqqe9` (`instructor_id`),
  CONSTRAINT `FK3ns8ghd3m06t8uxhbho1gqqe9` FOREIGN KEY (`instructor_id`) REFERENCES `account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `course_level` (
  `course_id` bigint NOT NULL,
  `cert_level` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  KEY `FK1sc11vscwxtx5t8stgelfi310` (`course_id`),
  CONSTRAINT `FK1sc11vscwxtx5t8stgelfi310` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `course_media` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `kind` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `sort_order` int NOT NULL,
  `url` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `course_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKmno33h94ad9eik7nf5lqp4b0y` (`course_id`),
  CONSTRAINT `FKmno33h94ad9eik7nf5lqp4b0y` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `course_region` (
  `course_id` bigint NOT NULL,
  `region` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  KEY `FKuveekl8utyur0yr06i1uv7wk` (`course_id`),
  CONSTRAINT `FKuveekl8utyur0yr06i1uv7wk` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `course_round` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `description` longtext COLLATE utf8mb4_unicode_ci,
  `free_count` int DEFAULT NULL,
  `per_session_price` int DEFAULT NULL,
  `platform_confirmed` bit(1) NOT NULL,
  `round_index` int DEFAULT NULL,
  `round_kind` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `course_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKkyby36kl8unynuu8it3hde6mb` (`course_id`),
  CONSTRAINT `FKkyby36kl8unynuu8it3hde6mb` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `discipline` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `active` bit(1) NOT NULL,
  `code` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `requires_certification` bit(1) NOT NULL,
  `sort_order` int NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_6o0nxtpchgrps1q1ogccw5vhw` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `enrollment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime DEFAULT NULL,
  `tuition_snapshot` int NOT NULL,
  `course_id` bigint DEFAULT NULL,
  `student_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKbhhcqkw1px6yljqg92m0sh2gt` (`course_id`),
  KEY `FKtbon18lxsy5o7qerc4wxb8l1o` (`student_id`),
  CONSTRAINT `FKbhhcqkw1px6yljqg92m0sh2gt` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`),
  CONSTRAINT `FKtbon18lxsy5o7qerc4wxb8l1o` FOREIGN KEY (`student_id`) REFERENCES `account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `enrollment_equipment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `item_ref` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `price_snapshot` int NOT NULL,
  `enrollment_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK4enwq5x5oi1x2fxvkvmnvi19u` (`enrollment_id`),
  CONSTRAINT `FK4enwq5x5oi1x2fxvkvmnvi19u` FOREIGN KEY (`enrollment_id`) REFERENCES `enrollment` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `enrollment_round` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `block_end` time DEFAULT NULL,
  `block_start` time DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  `date` date DEFAULT NULL,
  `done_at` datetime DEFAULT NULL,
  `entry_snapshot` int NOT NULL,
  `equipment_snapshot` int NOT NULL,
  `extra_snapshot` int NOT NULL,
  `rejection_reason` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `responded_at` datetime DEFAULT NULL,
  `round_index` int DEFAULT NULL,
  `round_kind` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `ticket_ref` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `venue_ref_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `session_id` bigint DEFAULT NULL,
  `course_round_id` bigint DEFAULT NULL,
  `enrollment_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK2wxkkrvjybvewer4w4g9b9kiu` (`session_id`),
  KEY `FKg9fqo4dokmc704fsp1ng5wdbj` (`course_round_id`),
  KEY `FKq7jxvcpm6if51lli031cd53gr` (`enrollment_id`),
  CONSTRAINT `FK2wxkkrvjybvewer4w4g9b9kiu` FOREIGN KEY (`session_id`) REFERENCES `availability_session` (`id`),
  CONSTRAINT `FKg9fqo4dokmc704fsp1ng5wdbj` FOREIGN KEY (`course_round_id`) REFERENCES `course_round` (`id`),
  CONSTRAINT `FKq7jxvcpm6if51lli031cd53gr` FOREIGN KEY (`enrollment_id`) REFERENCES `enrollment` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `enrollment_round_equipment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `item_ref` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `price_snapshot` int NOT NULL,
  `size` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `enrollment_round_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKhlc4ranh2q4g6c1hc3ia757hu` (`enrollment_round_id`),
  CONSTRAINT `FKhlc4ranh2q4g6c1hc3ia757hu` FOREIGN KEY (`enrollment_round_id`) REFERENCES `enrollment_round` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `enrollment_round_proposed_date` (
  `round_id` bigint NOT NULL,
  `proposed_date` date DEFAULT NULL,
  `date_order` int NOT NULL,
  PRIMARY KEY (`round_id`,`date_order`),
  CONSTRAINT `FKbibxm7r2m6vgbslkv0ylt2ge6` FOREIGN KEY (`round_id`) REFERENCES `enrollment_round` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `enrollment_round_proposed_slot` (
  `round_id` bigint NOT NULL,
  `proposed_block_end` time DEFAULT NULL,
  `proposed_block_start` time DEFAULT NULL,
  `proposed_date` date DEFAULT NULL,
  `proposed_ticket_ref` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `slot_order` int NOT NULL,
  PRIMARY KEY (`round_id`,`slot_order`),
  CONSTRAINT `FKqnms26rw1yc0le6ae7tynnfvg` FOREIGN KEY (`round_id`) REFERENCES `enrollment_round` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `enrollment_round_slot_history` (
  `round_id` bigint NOT NULL,
  `past_block_end` time DEFAULT NULL,
  `past_block_start` time DEFAULT NULL,
  `changed_at` datetime DEFAULT NULL,
  `past_date` date DEFAULT NULL,
  `past_ticket_ref` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `past_venue_ref` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `history_order` int NOT NULL,
  PRIMARY KEY (`round_id`,`history_order`),
  CONSTRAINT `FKp94i5mlrnicueksvh0upeajwu` FOREIGN KEY (`round_id`) REFERENCES `enrollment_round` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `equipment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `price` int DEFAULT NULL,
  `lecture_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKbaxndwjngfgjs39ver5hjdsy9` (`lecture_id`),
  CONSTRAINT `FKbaxndwjngfgjs39ver5hjdsy9` FOREIGN KEY (`lecture_id`) REFERENCES `lecture` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `equipment_stock` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `quantity` int DEFAULT NULL,
  `size` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `equipment_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKou155stjbmacstuhu4rmqvpu2` (`equipment_id`),
  CONSTRAINT `FKou155stjbmacstuhu4rmqvpu2` FOREIGN KEY (`equipment_id`) REFERENCES `equipment` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `firebase_token` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime DEFAULT NULL,
  `device_type` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `last_seen_at` datetime DEFAULT NULL,
  `token` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL,
  `account_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_firebase_token_token` (`token`),
  KEY `FK71dl4i7hp9n3rc14q11h5jqqp` (`account_id`),
  CONSTRAINT `FK71dl4i7hp9n3rc14q11h5jqqp` FOREIGN KEY (`account_id`) REFERENCES `account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `flyway_schema_history` (
  `installed_rank` int NOT NULL,
  `version` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `description` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `script` varchar(1000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `checksum` int DEFAULT NULL,
  `installed_by` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `installed_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `execution_time` int NOT NULL,
  `success` tinyint(1) NOT NULL,
  PRIMARY KEY (`installed_rank`),
  KEY `flyway_schema_history_s_idx` (`success`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `hibernate_sequence` (
  `next_val` bigint DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `identity_verification` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `birth` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `ci` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `di` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `gender` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `phone_number` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `provider` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `real_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `verified_at` datetime DEFAULT NULL,
  `account_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK4nw9u3ldahhf2xrso6mqnfl73` (`account_id`),
  CONSTRAINT `FK4nw9u3ldahhf2xrso6mqnfl73` FOREIGN KEY (`account_id`) REFERENCES `account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `instructor_application` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime DEFAULT NULL,
  `organization_code` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `organization_other` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `rejection_reason` longtext COLLATE utf8mb4_unicode_ci,
  `reviewed_at` datetime DEFAULT NULL,
  `status` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `submitted_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `account_id` bigint DEFAULT NULL,
  `identity_verification_id` bigint DEFAULT NULL,
  `reviewer_id` bigint DEFAULT NULL,
  `discipline_code` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_application_account_discipline` (`account_id`,`discipline_code`),
  KEY `FK8pej3475rkn7t4435rqgvnyra` (`identity_verification_id`),
  KEY `FK5wthcayykofmovwrmrt82fjs8` (`reviewer_id`),
  CONSTRAINT `FK5wthcayykofmovwrmrt82fjs8` FOREIGN KEY (`reviewer_id`) REFERENCES `account` (`id`),
  CONSTRAINT `FK8pej3475rkn7t4435rqgvnyra` FOREIGN KEY (`identity_verification_id`) REFERENCES `identity_verification` (`id`),
  CONSTRAINT `FKbq3nilr2myf0vmi1056q52jhk` FOREIGN KEY (`account_id`) REFERENCES `account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `instructor_certificate` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `fileurl` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `instructor_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK95jt2j5vh9rkixr4k3co6vgrm` (`instructor_id`),
  CONSTRAINT `FK95jt2j5vh9rkixr4k3co6vgrm` FOREIGN KEY (`instructor_id`) REFERENCES `account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `lecture` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `class_kind` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `description` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `is_closed` bit(1) DEFAULT NULL,
  `lecture_time` time DEFAULT NULL,
  `level` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `max_number` int DEFAULT NULL,
  `organization` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `period` int DEFAULT NULL,
  `price` int DEFAULT NULL,
  `region` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `registration_date` datetime DEFAULT NULL,
  `review_count` int DEFAULT NULL,
  `review_total_avg` float DEFAULT NULL,
  `title` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `instructor_id` bigint DEFAULT NULL,
  `location_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKfs80f5eypwnlr8l7jbjiirg35` (`instructor_id`),
  KEY `FKkfj5sp8cocbelcv7ixhmnn3x` (`location_id`),
  CONSTRAINT `FKfs80f5eypwnlr8l7jbjiirg35` FOREIGN KEY (`instructor_id`) REFERENCES `account` (`id`),
  CONSTRAINT `FKkfj5sp8cocbelcv7ixhmnn3x` FOREIGN KEY (`location_id`) REFERENCES `location` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `lecture_image` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `fileuri` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `lecture_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK7c11tsu0esf549lx0ildli0r7` (`lecture_id`),
  CONSTRAINT `FK7c11tsu0esf549lx0ildli0r7` FOREIGN KEY (`lecture_id`) REFERENCES `lecture` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `lecture_mark` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `account_id` bigint DEFAULT NULL,
  `lecture_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK1circa6cs9s26vao2ok451n01` (`account_id`),
  KEY `FKk8piu1tn34wmmm19y6ugggm9f` (`lecture_id`),
  CONSTRAINT `FK1circa6cs9s26vao2ok451n01` FOREIGN KEY (`account_id`) REFERENCES `account` (`id`),
  CONSTRAINT `FKk8piu1tn34wmmm19y6ugggm9f` FOREIGN KEY (`lecture_id`) REFERENCES `lecture` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `lecture_service_tags` (
  `lecture_id` bigint NOT NULL,
  `service_tags` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  KEY `FKcd8wcxounj7or0hyn8eoisjqp` (`lecture_id`),
  CONSTRAINT `FKcd8wcxounj7or0hyn8eoisjqp` FOREIGN KEY (`lecture_id`) REFERENCES `lecture` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `location` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `address` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `latitude` double DEFAULT NULL,
  `longitude` double DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `notification_outbox` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `attempts` int NOT NULL,
  `created_at` datetime NOT NULL,
  `last_error` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `next_attempt_at` datetime NOT NULL,
  `payload` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `recipient_account_id` bigint NOT NULL,
  `sent_at` datetime DEFAULT NULL,
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_outbox_status_next_attempt` (`status`,`next_attempt_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `payment` (
  `id` bigint NOT NULL,
  `equipment_rent_cost` int DEFAULT NULL,
  `lecture_cost` int DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `payment_order` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `amount` int NOT NULL,
  `approved_at` datetime DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  `method` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `order_id` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `order_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `payment_key` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `enrollment_id` bigint DEFAULT NULL,
  `enrollment_round_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_order_order_id` (`order_id`),
  KEY `FKotp6gggu43awmk49u8qoeqfv6` (`enrollment_id`),
  KEY `FKl7pv7xeb1ek65ab8vv1k700dv` (`enrollment_round_id`),
  CONSTRAINT `FKl7pv7xeb1ek65ab8vv1k700dv` FOREIGN KEY (`enrollment_round_id`) REFERENCES `enrollment_round` (`id`),
  CONSTRAINT `FKotp6gggu43awmk49u8qoeqfv6` FOREIGN KEY (`enrollment_id`) REFERENCES `enrollment` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `profile_photo` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `image_url` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `refund_order` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `amount` int NOT NULL,
  `created_at` datetime DEFAULT NULL,
  `reason` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `payment_order_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK4r59o6m907hq6vnm5788n41yg` (`payment_order_id`),
  CONSTRAINT `FK4r59o6m907hq6vnm5788n41yg` FOREIGN KEY (`payment_order_id`) REFERENCES `payment_order` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `reservation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `date_of_reservation` date DEFAULT NULL,
  `last_schedule_date_time` datetime DEFAULT NULL,
  `number_of_people` int DEFAULT NULL,
  `account_id` bigint DEFAULT NULL,
  `payment_id` bigint DEFAULT NULL,
  `review_id` bigint DEFAULT NULL,
  `schedule_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKpuht7aanh4i4be0i58jofg56b` (`account_id`),
  KEY `FK8g1s9tyunsjdv96dyiobv51bb` (`payment_id`),
  KEY `FKrqch34pw8aspb1gmgbdwjceya` (`review_id`),
  KEY `FKjhy65q5kmadkpjil9wlyp5o64` (`schedule_id`),
  CONSTRAINT `FK8g1s9tyunsjdv96dyiobv51bb` FOREIGN KEY (`payment_id`) REFERENCES `payment` (`id`),
  CONSTRAINT `FKjhy65q5kmadkpjil9wlyp5o64` FOREIGN KEY (`schedule_id`) REFERENCES `schedule` (`id`),
  CONSTRAINT `FKpuht7aanh4i4be0i58jofg56b` FOREIGN KEY (`account_id`) REFERENCES `account` (`id`),
  CONSTRAINT `FKrqch34pw8aspb1gmgbdwjceya` FOREIGN KEY (`review_id`) REFERENCES `review` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `reservation_equipment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `rent_number` int DEFAULT NULL,
  `reservation_id` bigint DEFAULT NULL,
  `schedule_equipment_stock_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKpft8kq6oc3s1vj27685hnu1xu` (`reservation_id`),
  KEY `FKkgm2cilf7jbygd3slq6cn8y0u` (`schedule_equipment_stock_id`),
  CONSTRAINT `FKkgm2cilf7jbygd3slq6cn8y0u` FOREIGN KEY (`schedule_equipment_stock_id`) REFERENCES `schedule_equipment_stock` (`id`),
  CONSTRAINT `FKpft8kq6oc3s1vj27685hnu1xu` FOREIGN KEY (`reservation_id`) REFERENCES `reservation` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `review` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `description` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `instructor_star` float DEFAULT NULL,
  `lecture_star` float DEFAULT NULL,
  `location_star` float DEFAULT NULL,
  `total_star_avg` float DEFAULT NULL,
  `write_date` date DEFAULT NULL,
  `lecture_id` bigint DEFAULT NULL,
  `writer_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKriucnk3m7hsvjnwtdxxoqp5l8` (`lecture_id`),
  KEY `FKsqonv0qdiobl9diogcugr40ej` (`writer_id`),
  CONSTRAINT `FKriucnk3m7hsvjnwtdxxoqp5l8` FOREIGN KEY (`lecture_id`) REFERENCES `lecture` (`id`),
  CONSTRAINT `FKsqonv0qdiobl9diogcugr40ej` FOREIGN KEY (`writer_id`) REFERENCES `account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `review_image` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `url` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `review_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK16wp089tx9nm0obc217gvdd6l` (`review_id`),
  CONSTRAINT `FK16wp089tx9nm0obc217gvdd6l` FOREIGN KEY (`review_id`) REFERENCES `review` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `round_venue` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `sort_order` int NOT NULL,
  `venue_ref_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `round_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK92sdxcjvn1tvy5bv17esl2pg0` (`round_id`),
  CONSTRAINT `FK92sdxcjvn1tvy5bv17esl2pg0` FOREIGN KEY (`round_id`) REFERENCES `course_round` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `round_venue_ticket` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `daypart` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `sort_order` int NOT NULL,
  `ticket_ref` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `round_venue_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKtg1n3h60do2kw8cvynci1ncht` (`round_venue_id`),
  CONSTRAINT `FKtg1n3h60do2kw8cvynci1ncht` FOREIGN KEY (`round_venue_id`) REFERENCES `round_venue` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `schedule` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `current_number` int DEFAULT NULL,
  `lecture_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKdhqdej1p3232l393s9b6i6924` (`lecture_id`),
  CONSTRAINT `FKdhqdej1p3232l393s9b6i6924` FOREIGN KEY (`lecture_id`) REFERENCES `lecture` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `schedule_date_time` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `date` date DEFAULT NULL,
  `end_time` time DEFAULT NULL,
  `start_time` time DEFAULT NULL,
  `schedule_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKpocjfcrmfgh8drtpf6gbxd56b` (`schedule_id`),
  CONSTRAINT `FKpocjfcrmfgh8drtpf6gbxd56b` FOREIGN KEY (`schedule_id`) REFERENCES `schedule` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `schedule_equipment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `price` int DEFAULT NULL,
  `schedule_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKtnlk2p9kgpjrqx0bqm0rau6ag` (`schedule_id`),
  CONSTRAINT `FKtnlk2p9kgpjrqx0bqm0rau6ag` FOREIGN KEY (`schedule_id`) REFERENCES `schedule` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `schedule_equipment_stock` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `quantity` int DEFAULT NULL,
  `size` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `total_rent_number` int DEFAULT NULL,
  `schedule_equipment_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKs3tjcunw51jnal2otglw3i28u` (`schedule_equipment_id`),
  CONSTRAINT `FKs3tjcunw51jnal2otglw3i28u` FOREIGN KEY (`schedule_equipment_id`) REFERENCES `schedule_equipment` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `venue` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `address` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  `equip_fee` int DEFAULT NULL,
  `equip_info` longtext COLLATE utf8mb4_unicode_ci,
  `latitude` double DEFAULT NULL,
  `locked_discipline_code` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `longitude` double DEFAULT NULL,
  `name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `scope` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `type` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `owner_id` bigint DEFAULT NULL,
  `address_detail` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `max_depth` int DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKmxvxcfqbes2maprrpe4dviv1j` (`owner_id`),
  CONSTRAINT `FKmxvxcfqbes2maprrpe4dviv1j` FOREIGN KEY (`owner_id`) REFERENCES `account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `venue_closure` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `monthly_weekday` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `type` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `venue_id` bigint DEFAULT NULL,
  `nth` int DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKi8koxw3m9tgup65uygst9ayn5` (`venue_id`),
  CONSTRAINT `FKi8koxw3m9tgup65uygst9ayn5` FOREIGN KEY (`venue_id`) REFERENCES `venue` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `venue_closure_nth` (
  `closure_id` bigint NOT NULL,
  `nth` int DEFAULT NULL,
  KEY `FKs2lrbof5y78g33y8wx6swn9ag` (`closure_id`),
  CONSTRAINT `FKs2lrbof5y78g33y8wx6swn9ag` FOREIGN KEY (`closure_id`) REFERENCES `venue_closure` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `venue_closure_weekday` (
  `closure_id` bigint NOT NULL,
  `weekday` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  KEY `FK8bxrr9qxp6x93l6mcmwg8oyqj` (`closure_id`),
  CONSTRAINT `FK8bxrr9qxp6x93l6mcmwg8oyqj` FOREIGN KEY (`closure_id`) REFERENCES `venue_closure` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `venue_daypart` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `fee` int DEFAULT NULL,
  `hold_hours` int DEFAULT NULL,
  `kind` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `open_end` time DEFAULT NULL,
  `open_start` time DEFAULT NULL,
  `sold` bit(1) NOT NULL,
  `time_mode` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `ticket_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKlstrxtq9t6wj547o5nmp2noxf` (`ticket_id`),
  CONSTRAINT `FKlstrxtq9t6wj547o5nmp2noxf` FOREIGN KEY (`ticket_id`) REFERENCES `venue_ticket` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `venue_equipment_extension` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `venue_ref_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `owner_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_owner_venue_ref` (`owner_id`,`venue_ref_id`),
  CONSTRAINT `FK5doh5s1iv4fn3xs9qo5ldqsm1` FOREIGN KEY (`owner_id`) REFERENCES `account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `venue_equipment_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `price` int NOT NULL,
  `size_format` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `sort_order` int NOT NULL,
  `profile_id` bigint DEFAULT NULL,
  `extension_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK4fddcdb5hpgjbpb3grio35y49` (`profile_id`),
  KEY `FKewwu2bt0n95xg95bxotdl5uom` (`extension_id`),
  CONSTRAINT `FK4fddcdb5hpgjbpb3grio35y49` FOREIGN KEY (`profile_id`) REFERENCES `venue_equipment_profile` (`id`),
  CONSTRAINT `FKewwu2bt0n95xg95bxotdl5uom` FOREIGN KEY (`extension_id`) REFERENCES `venue_equipment_extension` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `venue_equipment_item_size` (
  `item_id` bigint NOT NULL,
  `size_option` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `size_order` int NOT NULL,
  PRIMARY KEY (`item_id`,`size_order`),
  CONSTRAINT `FKn6re04h03mlbq4j7io2jsct4c` FOREIGN KEY (`item_id`) REFERENCES `venue_equipment_item` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `venue_equipment_profile` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `venue_ref_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `owner_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_owner_venue_ref` (`owner_id`,`venue_ref_id`),
  CONSTRAINT `FKk2oetwgt2v8yyoak4n9tffy3w` FOREIGN KEY (`owner_id`) REFERENCES `account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `venue_media` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `media_type` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `sort_order` int NOT NULL,
  `url` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `venue_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK88qt0c9rtafdemf5yv7e8jo73` (`venue_id`),
  CONSTRAINT `FK88qt0c9rtafdemf5yv7e8jo73` FOREIGN KEY (`venue_id`) REFERENCES `venue` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `venue_ticket` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `sort_order` int NOT NULL,
  `venue_id` bigint DEFAULT NULL,
  `ref` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK8gbsdvvwpbsrku42dnwm1fap` (`venue_id`),
  CONSTRAINT `FK8gbsdvvwpbsrku42dnwm1fap` FOREIGN KEY (`venue_id`) REFERENCES `venue` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `venue_ticket_discipline` (
  `ticket_id` bigint NOT NULL,
  `discipline_code` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  KEY `FKk76lqlsbhap4ou4lx973le7cn` (`ticket_id`),
  CONSTRAINT `FKk76lqlsbhap4ou4lx973le7cn` FOREIGN KEY (`ticket_id`) REFERENCES `venue_ticket` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `venue_time_block` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `end_time` time DEFAULT NULL,
  `sort_order` int NOT NULL,
  `start_time` time DEFAULT NULL,
  `daypart_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKm4jh46aaxabffytrk2lrugabp` (`daypart_id`),
  CONSTRAINT `FKm4jh46aaxabffytrk2lrugabp` FOREIGN KEY (`daypart_id`) REFERENCES `venue_daypart` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;
