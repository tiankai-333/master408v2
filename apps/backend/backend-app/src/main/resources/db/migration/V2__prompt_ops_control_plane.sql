-- =====================================================================
-- V2__prompt_ops_control_plane.sql
-- AI Prompt 控制平面（M6.5 P0+P1）：不可变版本 / 灰度发布 / 审批审计，
-- 并给 t_ai_usage_log 增加版本关联列，使运行日志可追溯到具体 Prompt 版本。
-- =====================================================================

-- 1. Prompt 的稳定业务标识（一个 prompt_key 对应一个 definition）
CREATE TABLE `ai_prompt_definition` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `prompt_key` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '稳定业务键，如 analysis.plato / tool.intent-router / chat.default',
  `name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `prompt_kind` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'analysis' COMMENT 'analysis | system | fragment',
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `create_user` int DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_prompt_key` (`prompt_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI Prompt 稳定业务标识';

-- 2. 不可变版本（内容在离开 draft 后只读；变更走新版本）
CREATE TABLE `ai_prompt_version` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `definition_id` bigint NOT NULL,
  `version_no` int NOT NULL COMMENT '每个 definition 内单调递增',
  `system_prompt` mediumtext COLLATE utf8mb4_unicode_ci,
  `user_prompt_template` mediumtext COLLATE utf8mb4_unicode_ci,
  `variables_json` text COLLATE utf8mb4_unicode_ci COMMENT '[{name,required,description}]',
  `model_params_json` text COLLATE utf8mb4_unicode_ci COMMENT '{temperature,maxTokens}，当前只存不应用',
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'draft' COMMENT 'draft|testing|awaiting_approval|rejected|canary|active|retired',
  `change_reason` text COLLATE utf8mb4_unicode_ci,
  `risk_note` text COLLATE utf8mb4_unicode_ci,
  `created_by` int DEFAULT NULL,
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `approved_by` int DEFAULT NULL,
  `approved_time` datetime DEFAULT NULL,
  `approve_comment` text COLLATE utf8mb4_unicode_ci,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_def_version` (`definition_id`,`version_no`),
  KEY `idx_def_status` (`definition_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI Prompt 不可变版本';

-- 3. 发布记录：每个 (definition, environment) 一行，记录当前 stable / canary / 灰度比例 / kill-switch
CREATE TABLE `ai_prompt_release` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `definition_id` bigint NOT NULL,
  `environment` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default',
  `stable_version_id` bigint NOT NULL COMMENT '当前稳定版本（服务 100-canary% 流量）',
  `canary_version_id` bigint DEFAULT NULL COMMENT '灰度候选版本；NULL 表示无灰度',
  `canary_percent` int NOT NULL DEFAULT '0' COMMENT '0..100，命中 canary 的流量比例',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'active' COMMENT 'active|disabled（disabled=kill switch，冻结灰度仍服务 stable）',
  `released_by` int DEFAULT NULL,
  `release_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_def_env` (`definition_id`,`environment`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI Prompt 发布与灰度';

-- 4. 审批与操作审计
CREATE TABLE `ai_prompt_audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `definition_id` bigint DEFAULT NULL,
  `version_id` bigint DEFAULT NULL,
  `release_id` bigint DEFAULT NULL,
  `action` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'create|edit_draft|submit|approve|reject|canary|promote|rollback|kill_switch|enable|test',
  `from_version_id` bigint DEFAULT NULL,
  `to_version_id` bigint DEFAULT NULL,
  `detail_json` text COLLATE utf8mb4_unicode_ci,
  `reason` text COLLATE utf8mb4_unicode_ci,
  `operator_id` int DEFAULT NULL,
  `operator_name` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `operate_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `ip_address` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_def_time` (`definition_id`,`operate_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI Prompt 操作审计';

-- 5. t_ai_usage_log 增加版本关联列（幂等守卫，沿用 06 风格）
SET @dbname = DATABASE();

SET @colname = 'prompt_key';
SET @preparedStatement = (SELECT IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 't_ai_usage_log' AND COLUMN_NAME = @colname) > 0,
  'SELECT 1',
  'ALTER TABLE t_ai_usage_log ADD COLUMN prompt_key VARCHAR(128) DEFAULT NULL AFTER key_source'
));
PREPARE alterIfNotExists FROM @preparedStatement; EXECUTE alterIfNotExists; DEALLOCATE PREPARE alterIfNotExists;

SET @colname = 'prompt_version_id';
SET @preparedStatement = (SELECT IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 't_ai_usage_log' AND COLUMN_NAME = @colname) > 0,
  'SELECT 1',
  'ALTER TABLE t_ai_usage_log ADD COLUMN prompt_version_id BIGINT DEFAULT NULL AFTER prompt_key'
));
PREPARE alterIfNotExists FROM @preparedStatement; EXECUTE alterIfNotExists; DEALLOCATE PREPARE alterIfNotExists;

SET @colname = 'prompt_release_id';
SET @preparedStatement = (SELECT IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 't_ai_usage_log' AND COLUMN_NAME = @colname) > 0,
  'SELECT 1',
  'ALTER TABLE t_ai_usage_log ADD COLUMN prompt_release_id BIGINT DEFAULT NULL AFTER prompt_version_id'
));
PREPARE alterIfNotExists FROM @preparedStatement; EXECUTE alterIfNotExists; DEALLOCATE PREPARE alterIfNotExists;

CREATE INDEX `idx_ai_usage_prompt_version` ON `t_ai_usage_log` (`prompt_version_id`);
CREATE INDEX `idx_ai_usage_prompt_key` ON `t_ai_usage_log` (`prompt_key`);
