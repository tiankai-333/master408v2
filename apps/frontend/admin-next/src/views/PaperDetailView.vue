<template>
  <div class="page-card" v-loading="loading">
    <el-page-header :content="paper?.name || '试卷浏览'" @back="goBack" />

    <el-alert
      v-if="loadError"
      :title="loadError"
      type="error"
      :closable="false"
      show-icon
      style="margin-top: 16px"
    >
      <el-button size="small" @click="load">重试</el-button>
    </el-alert>

    <template v-if="paper">
      <el-descriptions :column="3" border style="margin-top: 16px">
        <el-descriptions-item label="年份">{{ paper.year ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="题数">{{ paper.questionCount }}</el-descriptions-item>
        <el-descriptions-item label="导入告警">
          <el-tag :type="(paper.warningCount || 0) > 0 ? 'warning' : 'success'" size="small">
            {{ paper.warningCount }} 条
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="源指纹" :span="2">
          <span class="mono">{{ paper.sourceSha256 || '—' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="来源页">
          <a v-if="paper.sourceUrl" :href="paper.sourceUrl" target="_blank" rel="noopener noreferrer">
            {{ paper.sourceUrl }}
          </a>
          <span v-else class="muted">—</span>
        </el-descriptions-item>
      </el-descriptions>

      <el-alert
        v-if="paper.warnings && paper.warnings.length"
        type="warning"
        :closable="false"
        show-icon
        style="margin-top: 12px"
        title="导入盘点（缺失内容如实列出，非展示错误）"
      >
        <div v-for="(w, i) in paper.warnings" :key="i" class="warning-line">{{ w }}</div>
      </el-alert>

      <h4>题目（按卷面题号）</h4>
      <el-table :data="paper.questions" border size="small">
        <el-table-column prop="no" label="题号" width="70" />
        <el-table-column prop="subject" label="学科" width="130" />
        <el-table-column label="题型" width="80">
          <template #default="{ row }">{{ TYPE_LABELS[row.questionType] || row.questionType }}</template>
        </el-table-column>
        <el-table-column prop="shortStem" label="题干（纯文本投影）" min-width="360" show-overflow-tooltip />
        <el-table-column label="答案" width="80">
          <template #default="{ row }">{{ row.correct ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="复杂内容" width="110">
          <template #default="{ row }">
            <span v-if="!row.warningFlags" class="muted">—</span>
            <span v-else>{{ flagsLabel(row.warningFlags) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="上线阀" width="90">
          <template #default="{ row }">
            <el-tag :type="row.displayReady === 1 ? 'success' : 'info'" size="small">
              {{ row.displayReady === 1 ? '就绪' : '未就绪' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="openQuestion(row.no)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <el-drawer v-model="drawer" :title="`第 ${currentNo} 题`" size="60%">
      <div v-loading="qLoading">
        <el-alert
          v-if="qError"
          :title="qError"
          type="error"
          :closable="false"
          show-icon
        />
        <template v-if="question">
          <el-descriptions :column="2" border size="small" style="margin-bottom: 12px">
            <el-descriptions-item label="题号">{{ question.no }}</el-descriptions-item>
            <el-descriptions-item label="学科">{{ question.subject ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="题型">{{ TYPE_LABELS[question.questionType] || question.questionType }}</el-descriptions-item>
            <el-descriptions-item label="答案">{{ question.correct ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="标签" :span="2">
              {{ question.tags && question.tags.length ? question.tags.join('、') : '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="原始来源" :span="2">
              <a v-if="question.sourceRef" :href="question.sourceRef" target="_blank" rel="noopener noreferrer">
                {{ question.sourceRef }}
              </a>
            </el-descriptions-item>
            <el-descriptions-item v-if="question.statusReason" label="状态原因" :span="2">
              {{ question.statusReason }}
            </el-descriptions-item>
          </el-descriptions>
          <h5>题干与选项</h5>
          <QuestionHtml :html="question.stemHtml || ''" />
          <template v-if="question.analysisHtml">
            <h5>参考答案与解析</h5>
            <QuestionHtml :html="question.analysisHtml" />
            <el-alert
              v-if="analysisSourceLabel"
              type="info"
              :closable="false"
              :title="`解析来源：${analysisSourceLabel}`"
              style="margin-top: 8px"
            />
          </template>
          <el-alert
            v-else
            type="info"
            :closable="false"
            title="来源未提供解析（导入时如实入库为空，非展示丢失）"
            style="margin-top: 12px"
          />
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import QuestionHtml from '@/components/QuestionHtml.vue'
import { fetchPaperDetail, fetchPaperQuestion } from '@/api/papers'

const TYPE_LABELS = { 1: '单选', 2: '多选', 3: '判断', 4: '填空', 5: '简答' }
const FLAG_LABELS = { 1: '外链图', 2: '公式', 4: '代码', 8: '表格', 16: '内联图' }

const route = useRoute()
const router = useRouter()
const paper = ref(null)
const loading = ref(false)
const loadError = ref('')
const drawer = ref(false)
const qLoading = ref(false)
const qError = ref('')
const question = ref(null)
const currentNo = ref(null)

function flagsLabel(flags) {
  return Object.entries(FLAG_LABELS)
    .filter(([bit]) => flags & Number(bit))
    .map(([, label]) => label)
    .join('+')
}

function goBack() {
  const back = window.history.state && window.history.state.back
  if (back && !String(back).startsWith('/login')) {
    router.back()
  } else {
    router.push({ name: 'papers' })
  }
}

function openQuestion(no) {
  currentNo.value = no
  question.value = null
  qError.value = ''
  drawer.value = true
  loadQuestion(no)
}

// 解析来源可见性：legacy 补入 = 候选未核（旧爬虫纯文本，未逐题审校）；
// HTML 源（综合题）标来源；未匹配缺失由"未提供"提示覆盖。
const analysisSourceLabel = ref('')
function updateAnalysisSource(q) {
  const src = q?.assets?.analysis_source
  if (src && src.file) {
    analysisSourceLabel.value = `旧爬虫 ${src.file}（候选·未逐题核审，仅年份/题号/答案/题干启发式对账）`
  } else if (q?.questionType === 5) {
    analysisSourceLabel.value = 'HTML 来源页（solution 块原文）'
  } else {
    analysisSourceLabel.value = ''
  }
}

async function loadQuestion(no) {
  qLoading.value = true
  try {
    const body = await fetchPaperQuestion(route.params.id, no)
    question.value = body.response
    updateAnalysisSource(question.value)
  } catch (err) {
    qError.value = err?.message || '题目详情加载失败'
  } finally {
    qLoading.value = false
  }
}

async function load() {
  loading.value = true
  loadError.value = ''
  paper.value = null
  try {
    const body = await fetchPaperDetail(route.params.id)
    paper.value = body.response
  } catch (err) {
    loadError.value = err?.message || '试卷加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.mono {
  font-family: monospace;
  word-break: break-all;
}
.warning-line {
  line-height: 1.7;
}
</style>
