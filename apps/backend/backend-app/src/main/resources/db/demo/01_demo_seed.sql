-- =====================================================================
-- Master408 public demo seed
--
-- Purpose:
--   Provide a minimal, original data set for a fresh development database.
--   This file is intentionally NOT a Flyway migration. Import it manually
--   after Flyway has created the schema, so production startup never inserts
--   demonstration records automatically.
--
-- Copyright:
--   The question and explanation below were written specifically for this
--   repository. They are not copied from an exam, course, crawler or book.
-- =====================================================================

SET NAMES utf8mb4;

INSERT INTO `t_subject`
    (`name`, `level`, `level_name`, `item_order`, `deleted`)
SELECT
    '公开演示科目', 1, 'Demo', 1, b'0'
WHERE NOT EXISTS (
    SELECT 1
    FROM `t_subject`
    WHERE `name` = '公开演示科目' AND `deleted` = b'0'
);

SET @demo_subject_id = (
    SELECT `id`
    FROM `t_subject`
    WHERE `name` = '公开演示科目' AND `deleted` = b'0'
    ORDER BY `id`
    LIMIT 1
);

INSERT INTO `knowledge_point`
    (`name`, `subject_id`, `parent_id`, `description`, `level`,
     `sort_order`, `deleted`)
SELECT
    '算法复杂度入门',
    @demo_subject_id,
    NULL,
    '用于验证知识目录、题目关联和 AI 教学链路的原创演示知识点。',
    1,
    1,
    b'0'
WHERE NOT EXISTS (
    SELECT 1
    FROM `knowledge_point`
    WHERE `subject_id` = @demo_subject_id
      AND `name` = '算法复杂度入门'
      AND `deleted` = b'0'
);

SET @demo_knowledge_point_id = (
    SELECT `id`
    FROM `knowledge_point`
    WHERE `subject_id` = @demo_subject_id
      AND `name` = '算法复杂度入门'
      AND `deleted` = b'0'
    ORDER BY `id`
    LIMIT 1
);

INSERT INTO `t_question`
    (`question_type`, `subject_id`, `title`, `options`, `correct_answer`,
     `analysis`, `difficulty`, `knowledge_point`, `source`, `tags`,
     `title_text`, `analysis_text`, `content_format`, `has_image`,
     `has_code`, `score`, `status`, `deleted`)
SELECT
    1,
    @demo_subject_id,
    '<p>某算法依次访问长度为 n 的数组中的每个元素一次。忽略常数开销，它的时间复杂度是什么？</p>',
    '["O(1)","O(log n)","O(n)","O(n²)"]',
    'C',
    '<p>访问次数随输入规模 n 线性增长，因此时间复杂度为 O(n)。常数次初始化和比较不会改变复杂度的数量级。</p>',
    1,
    '算法复杂度入门',
    'public-demo',
    '原创示例,时间复杂度',
    '某算法依次访问长度为 n 的数组中的每个元素一次。忽略常数开销，它的时间复杂度是什么？',
    '访问次数随输入规模 n 线性增长，因此时间复杂度为 O(n)。',
    'html',
    b'0',
    b'0',
    2,
    1,
    b'0'
WHERE NOT EXISTS (
    SELECT 1
    FROM `t_question`
    WHERE `source` = 'public-demo'
      AND `title_text` LIKE '某算法依次访问长度为 n 的数组中的每个元素一次%'
      AND `deleted` = b'0'
);

SET @demo_question_id = (
    SELECT `id`
    FROM `t_question`
    WHERE `source` = 'public-demo'
      AND `title_text` LIKE '某算法依次访问长度为 n 的数组中的每个元素一次%'
      AND `deleted` = b'0'
    ORDER BY `id`
    LIMIT 1
);

INSERT INTO `question_knowledge_point`
    (`question_id`, `knowledge_point_id`, `relevance`)
SELECT
    @demo_question_id, @demo_knowledge_point_id, 1.00
WHERE @demo_question_id IS NOT NULL
  AND @demo_knowledge_point_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM `question_knowledge_point`
      WHERE `question_id` = @demo_question_id
        AND `knowledge_point_id` = @demo_knowledge_point_id
  );
