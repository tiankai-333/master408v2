-- Unified trace dimensions for model call -> prompt release -> user feedback.
ALTER TABLE `t_ai_usage_log`
    ADD COLUMN `request_id` VARCHAR(64) DEFAULT NULL AFTER `id`,
    ADD COLUMN `engine` VARCHAR(32) DEFAULT NULL AFTER `request_id`,
    ADD COLUMN `mode` VARCHAR(16) DEFAULT NULL AFTER `engine`,
    ADD COLUMN `usage_source` VARCHAR(16) DEFAULT NULL AFTER `mode`,
    ADD COLUMN `conversation_id` VARCHAR(160) DEFAULT NULL AFTER `usage_source`,
    ADD COLUMN `first_token_latency_ms` INT DEFAULT NULL AFTER `duration_ms`,
    ADD UNIQUE INDEX `uk_ai_usage_request_id` (`request_id`),
    ADD INDEX `idx_ai_usage_observation` (`engine`, `mode`, `success`, `create_time`),
    ADD INDEX `idx_ai_usage_conversation` (`conversation_id`, `create_time`);
