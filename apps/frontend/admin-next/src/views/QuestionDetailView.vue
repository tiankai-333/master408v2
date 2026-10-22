<template>
  <div class="page-card" v-loading="loading">
    <el-page-header content="题目详情" @back="goBack" />

    <el-alert
      v-if="loadError"
      :title="loadError"
      type="error"
      :closable="false"
      show-icon
      style="margin-top: 16px"
    >
      <el-button size="small" @click="loadDetail">重试</el-button>
      <el-button size="small" type="primary" @click="toList">返回列表</el-button>
    </el-alert>

    <el-empty v-else-if="!loading && !detail" description="暂无题目详情" style="margin-top: 16px" />

    <template v-if="detail">
      <el-descriptions :column="3" border style="margin-top: 16px">
        <el-descriptions-item label="ID">{{ detail.id }}</el-descriptions-item>
        <el-descriptions-item label="题型">{{ TYPE_LABELS[detail.questionType] || detail.questionType }}</el-descriptions-item>
        <el-descriptions-item label="学科">{{ detail.subjectName ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="来源">{{ detail.sourceName ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="年份/题号">{{ detail.sourceYear ?? '—' }} / 第{{ detail.sourceQuestionNo }}题</el-descriptions-item>
        <el-descriptions-item label="答案">{{ detail.correct ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="分值">{{ detail.score ?? '—' }}<span class="muted">（源数据无分值事实）</span></el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusTagType(detail.status)" size="small">{{ STATUS_LABELS[detail.status] || detail.status }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="上线阀">
          <el-tag :type="detail.displayReady === 1 ? 'success' : 'info'" size="small">
            {{ detail.displayReady === 1 ? '就绪' : '未就绪' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="授权标记">{{ detail.licenseTag }}</el-descriptions-item>
        <el-descriptions-item label="批次">{{ detail.batchTag ?? detail.batchId ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="导入时间">{{ detail.createTime }}</el-descriptions-item>
        <el-descriptions-item label="原始来源" :span="3">
          <a v-if="detail.sourceRef" :href="detail.sourceRef" target="_blank" rel="noopener noreferrer">{{ detail.sourceRef }}</a>
          <span v-else class="muted">—</span>
        </el-descriptions-item>
        <el-descriptions-item v-if="detail.statusReason" label="状态原因" :span="3">{{ detail.statusReason }}</el-descriptions-item>
      </el-descriptions>

      <h4>题干与选项</h4>
      <QuestionHtml :html="detail.stemHtml || ''" />

      <h4>解析</h4>
      <QuestionHtml :html="detail.analysisHtml || ''" />

      <h4>纯文本投影（stem_text）</h4>
      <p class="muted pre-wrap">{{ detail.stemText }}</p>
    </template>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import QuestionHtml from '@/components/QuestionHtml.vue'
import { fetchQuestionDetail } from '@/api/questions'

const TYPE_LABELS = { 1: '单选', 2: '多选', 3: '判断', 4: '填空', 5: '简答' }
const STATUS_LABELS = { 1: '正常', 2: '停用', 3: '紧急撤下' }

const route = useRoute()
const router = useRouter()
const detail = ref(null)
const loading = ref(false)
const loadError = ref('')

function statusTagType(status) {
  if (status === 1) return 'success'
  if (status === 2) return 'warning'
  return 'danger'
}

// 返回：上一条是应用内受保护路由则 back()；直接打开详情（无历史）或
// 经登录跳转而来（上一条是 /login）时，落到列表页而非登录页
function goBack() {
  const back = window.history.state && window.history.state.back
  if (back && !String(back).startsWith('/login')) {
    router.back()
  } else {
    toList()
  }
}

function toList() {
  router.push({ name: 'questions' })
}

async function loadDetail() {
  loading.value = true
  loadError.value = ''
  detail.value = null
  try {
    const body = await fetchQuestionDetail(route.params.id)
    detail.value = body.response
  } catch (err) {
    // 失败必须可见：不冒充加载成功，也不留旧详情
    loadError.value = err?.message || '题目详情加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(loadDetail)
</script>

<style scoped>
.pre-wrap {
  white-space: pre-wrap;
  margin: 4px 0 0;
}
</style>
