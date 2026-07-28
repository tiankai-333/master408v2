-- Persist reproducible AI evaluation runs and per-case evidence.

CREATE TABLE IF NOT EXISTS `ai_evaluation_run` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `dataset_version` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `candidate_label` varchar(150) COLLATE utf8mb4_unicode_ci NOT NULL,
  `engine` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'queued',
  `case_count` int NOT NULL DEFAULT '0',
  `passed_count` int NOT NULL DEFAULT '0',
  `average_quality_score` decimal(8,2) DEFAULT NULL,
  `estimated_input_tokens` int NOT NULL DEFAULT '0',
  `estimated_output_tokens` int NOT NULL DEFAULT '0',
  `estimated_cost` decimal(14,8) NOT NULL DEFAULT '0.00000000',
  `average_latency_ms` int DEFAULT NULL,
  `p95_latency_ms` int DEFAULT NULL,
  `error_message` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_by` int DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `start_time` datetime DEFAULT NULL,
  `finish_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ai_eval_run_time` (`create_time`),
  KEY `idx_ai_eval_run_dataset_candidate` (`dataset_version`,`candidate_label`),
  KEY `idx_ai_eval_run_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='AI fixed evaluation run';

CREATE TABLE IF NOT EXISTS `ai_evaluation_case_result` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `run_id` bigint NOT NULL,
  `case_id` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `category` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `prompt_key` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `prompt_version_id` bigint DEFAULT NULL,
  `prompt_release_id` bigint DEFAULT NULL,
  `passed` bit(1) NOT NULL DEFAULT b'0',
  `quality_score` decimal(8,2) NOT NULL DEFAULT '0.00',
  `concept_coverage` decimal(8,4) NOT NULL DEFAULT '0.0000',
  `missing_concepts_json` longtext COLLATE utf8mb4_unicode_ci,
  `forbidden_hits_json` longtext COLLATE utf8mb4_unicode_ci,
  `response_text` longtext COLLATE utf8mb4_unicode_ci,
  `response_chars` int NOT NULL DEFAULT '0',
  `estimated_input_tokens` int NOT NULL DEFAULT '0',
  `estimated_output_tokens` int NOT NULL DEFAULT '0',
  `estimated_cost` decimal(14,8) NOT NULL DEFAULT '0.00000000',
  `end_to_end_latency_ms` int NOT NULL DEFAULT '0',
  `failure_reason` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `error_message` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_eval_case_run` (`run_id`,`case_id`),
  KEY `idx_ai_eval_case_id` (`case_id`),
  KEY `idx_ai_eval_case_prompt` (`prompt_key`,`prompt_version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='AI fixed evaluation case result';
