// 临时夹具入口：仅挂载实际 QuestionHtml 组件（非渲染副本）
import { createApp, h } from 'vue'
import QuestionHtml from '../src/components/QuestionHtml.vue'
createApp({ render: () => h(QuestionHtml, { html: window.__FIXTURE_HTML__ }) }).mount('#app')
