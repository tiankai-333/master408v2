<template>
  <div class="question-html" v-html="html"></div>
</template>

<script>
/**
 * v-html 的唯一入口（安全边界）：
 * - 只允许绑定 stemHtml / analysisHtml 两个字段——二者在导入管道入库前
 *   已用 jsoup 白名单（仅 div/p 标签 + class/data-key 属性）消毒；
 * - 其余任何字段（sourceRef、stemText、batchTag、licenseTag、statusReason、
 *   错误信息、用户输入回显）一律文本插值，禁止进入本组件；
 * - 新增可 v-html 字段的前提：该字段先进入后端导入器的消毒管线。
 */
export default {
  name: 'QuestionHtml',
  props: {
    html: {
      type: String,
      required: true
    }
  }
}
</script>
