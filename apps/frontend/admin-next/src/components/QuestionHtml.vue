<template>
  <div class="question-html" v-html="renderedHtml"></div>
</template>

<script>
import katex from 'katex'
import 'katex/dist/katex.min.css'

/**
 * v-html 的唯一入口（安全边界）：
 * - 只允许绑定 stemHtml / analysisHtml 两个字段——二者在导入管道入库前
 *   已用 jsoup 白名单（结构标签 + class/data-key 属性）消毒；
 * - 其余任何字段（sourceRef、stemText、batchTag、licenseTag、statusReason、
 *   错误信息、用户输入回显）一律文本插值，禁止进入本组件；
 * - 新增可 v-html 字段的前提：该字段先进入后端导入器的消毒管线。
 * v1 公式：消毒后 HTML 中的 `\(...\)` / `\[...\]` 定界文本由 katex.render 在字符串层
 * 渲染后再交给 v-html（真实浏览器已验证 3 公式节点 + 表格，见 10.13 evidence）。
 * 弃用 auto-render 贡献包属路线选择（字符串层渲染更可测）；早先"auto-render 静默
 * no-op"归因系当时夹具输入损坏（heredoc 剥反斜杠），并非该库的技术事实。
 * trust:false 禁用 KaTeX 的 HTML/URL 信任扩展，throwOnError:false 失败保留原文。
 */
const DELIMITERS = [
  { left: '\\[', right: '\\]', display: true },
  { left: '\\(', right: '\\)', display: false }
]

function renderTextDelimiters(text) {
  // 依次按定界符切分；段内文本原样保留（消毒后文本，非攻击面）
  let html = text
  for (const { left, right, display } of DELIMITERS) {
    html = html.split(left + right).join('\u0000') // 防空对：无内容的定界符对先移除
    const re = new RegExp(left.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
        + '([\\s\\S]*?)' + right.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'g')
    html = html.replace(re, (whole, tex) => {
      try {
        return katex.renderToString(tex, {
          displayMode: display, throwOnError: false, trust: false, strict: 'ignore'
        })
      } catch (e) {
        // 不静默：渲染失败必须可见（控制台），同时保留原文避免整段丢失
        console.error('[QuestionHtml] katex render failed:', e && e.message, e)
        return whole
      }
    })
  }
  return html
}

export default {
  name: 'QuestionHtml',
  props: {
    html: {
      type: String,
      required: true
    }
  },
  data() {
    return { renderedHtml: '' }
  },
  watch: {
    html: {
      handler: 'renderFormulas',
      immediate: true
    }
  },
  methods: {
    renderFormulas() {
      // 在字符串层完成公式渲染再交给 v-html；不修改已挂载 DOM，避免与 Vue diff 冲突。
      // 输入已是导入管线消毒产物，渲染仅发生在定界符内文本。
      this.renderedHtml = this.html ? renderTextDelimiters(this.html) : ''
    }
  }
}
</script>

<style scoped>
.question-html :deep(table) {
  border-collapse: collapse;
  margin: 8px 0;
}
.question-html :deep(th),
.question-html :deep(td) {
  border: 1px solid var(--el-border-color, #dcdfe6);
  padding: 4px 10px;
  text-align: left;
}
.question-html :deep(th) {
  background: var(--el-fill-color-light, #f5f7fa);
}
.question-html :deep(sup),
.question-html :deep(sub) {
  line-height: 0;
}
</style>
