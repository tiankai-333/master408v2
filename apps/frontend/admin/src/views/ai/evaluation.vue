<template>
  <div class="app-container evaluation-page">
    <el-alert
      title="评测会调用当前后端实际配置的模型并产生 Token 消耗；contract 用例只由隔离测试执行，不会发送给模型。"
      type="warning"
      :closable="false"
      show-icon
      class="section-gap"
    />

    <el-row :gutter="16">
      <el-col :span="14">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <div>
                <b>固定数据集</b>
                <el-tag v-if="dataset.version" size="small" class="left-gap">{{ dataset.version }}</el-tag>
              </div>
              <el-button :loading="loadingDataset" @click="loadDataset">刷新</el-button>
            </div>
          </template>

          <p class="description">{{ dataset.description }}</p>
          <el-table
            ref="caseTableRef"
            :data="modelCases"
            row-key="id"
            border
            max-height="430"
            @selection-change="selectedCases = $event"
          >
            <el-table-column type="selection" width="46" />
            <el-table-column prop="id" label="Case ID" min-width="190" />
            <el-table-column prop="category" label="分类" width="90" />
            <el-table-column prop="style" label="讲法" width="110" />
            <el-table-column prop="question" label="问题" min-width="260" show-overflow-tooltip />
          </el-table>
        </el-card>
      </el-col>

      <el-col :span="10">
        <el-card shadow="never">
          <template #header><b>启动候选评测</b></template>
          <el-form label-position="top">
            <el-form-item label="候选标签">
              <el-input
                v-model="candidateLabel"
                maxlength="150"
                show-word-limit
                placeholder="例如 spring-deepseek-prompt-v2"
              />
            </el-form-item>
            <el-form-item label="执行范围">
              <span>{{ selectedCases.length ? `已选择 ${selectedCases.length} 条` : `全部 ${modelCases.length} 条模型用例` }}</span>
            </el-form-item>
            <el-button type="primary" :loading="starting" @click="startRun">
              启动异步评测
            </el-button>
          </el-form>

          <el-divider />
          <div class="contract-summary">
            <b>契约用例</b>
            <p>{{ contractCases.length }} 条 invalid/failure 用例由 JUnit Mock 验证，不消耗真实模型额度。</p>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="section-gap">
      <template #header>
        <div class="card-header">
          <b>运行记录</b>
          <el-button :loading="loadingRuns" @click="loadRuns">刷新</el-button>
        </div>
      </template>
      <el-table :data="runs" border>
        <el-table-column prop="id" label="Run" width="80" />
        <el-table-column prop="candidateLabel" label="候选标签" min-width="180" />
        <el-table-column prop="datasetVersion" label="数据集" min-width="180" />
        <el-table-column prop="engine" label="引擎" width="100" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="通过" width="90">
          <template #default="{ row }">{{ row.passedCount || 0 }}/{{ row.caseCount || 0 }}</template>
        </el-table-column>
        <el-table-column prop="averageQualityScore" label="质量分" width="90" />
        <el-table-column label="估算 Token" width="110">
          <template #default="{ row }">
            {{ (row.estimatedInputTokens || 0) + (row.estimatedOutputTokens || 0) }}
          </template>
        </el-table-column>
        <el-table-column label="估算费用" width="110">
          <template #default="{ row }">¥{{ formatNumber(row.estimatedCost, 6) }}</template>
        </el-table-column>
        <el-table-column prop="averageLatencyMs" label="平均延迟(ms)" width="125" />
        <el-table-column prop="p95LatencyMs" label="P95(ms)" width="90" />
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="showDetail(row.id)">证据</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="section-gap">
      <template #header><b>同版本候选对比</b></template>
      <div class="compare-form">
        <el-select v-model="baselineRunId" placeholder="基线 Run" filterable>
          <el-option
            v-for="run in completedRuns"
            :key="run.id"
            :label="`#${run.id} ${run.candidateLabel}`"
            :value="run.id"
          />
        </el-select>
        <span>→</span>
        <el-select v-model="candidateRunId" placeholder="候选 Run" filterable>
          <el-option
            v-for="run in completedRuns"
            :key="run.id"
            :label="`#${run.id} ${run.candidateLabel}`"
            :value="run.id"
          />
        </el-select>
        <el-button
          type="primary"
          :disabled="!baselineRunId || !candidateRunId || baselineRunId === candidateRunId"
          @click="compareRuns"
        >
          对比
        </el-button>
      </div>
      <el-descriptions v-if="comparison" :column="5" border class="section-gap">
        <el-descriptions-item label="质量分 Δ">{{ signed(comparison.qualityScoreDelta, 2) }}</el-descriptions-item>
        <el-descriptions-item label="通过数 Δ">{{ signed(comparison.passedCountDelta, 0) }}</el-descriptions-item>
        <el-descriptions-item label="费用 Δ">{{ signed(comparison.estimatedCostDelta, 6) }}</el-descriptions-item>
        <el-descriptions-item label="平均延迟 Δ">{{ signed(comparison.averageLatencyMsDelta, 0) }} ms</el-descriptions-item>
        <el-descriptions-item label="P95 Δ">{{ signed(comparison.p95LatencyMsDelta, 0) }} ms</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-dialog v-model="detailVisible" title="逐条评测证据" width="90%" top="5vh">
      <el-table :data="detail.results || []" border max-height="650">
        <el-table-column prop="caseId" label="Case" min-width="180" />
        <el-table-column label="通过" width="70">
          <template #default="{ row }">
            <el-tag :type="row.passed ? 'success' : 'danger'" size="small">{{ row.passed ? '是' : '否' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="qualityScore" label="质量分" width="85" />
        <el-table-column prop="conceptCoverage" label="概念覆盖" width="100" />
        <el-table-column prop="promptKey" label="Prompt" min-width="160" />
        <el-table-column prop="promptVersionId" label="版本" width="75" />
        <el-table-column prop="endToEndLatencyMs" label="延迟(ms)" width="95" />
        <el-table-column prop="failureReason" label="失败原因" min-width="150" />
        <el-table-column prop="responseText" label="模型回答" min-width="360" show-overflow-tooltip />
        <el-table-column prop="errorMessage" label="错误" min-width="180" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import aiEvaluationApi from '@/api/aiEvaluation'

const dataset = ref({ cases: [] })
const runs = ref([])
const selectedCases = ref([])
const candidateLabel = ref('')
const loadingDataset = ref(false)
const loadingRuns = ref(false)
const starting = ref(false)
const detailVisible = ref(false)
const detail = ref({ results: [] })
const baselineRunId = ref(null)
const candidateRunId = ref(null)
const comparison = ref(null)
let pollTimer = null

const modelCases = computed(() => (dataset.value.cases || []).filter(item => item.executionMode === 'model'))
const contractCases = computed(() => (dataset.value.cases || []).filter(item => item.executionMode !== 'model'))
const completedRuns = computed(() => runs.value.filter(item => item.status === 'completed'))
const hasActiveRuns = computed(() => runs.value.some(item => ['queued', 'running'].includes(item.status)))

async function loadDataset() {
  loadingDataset.value = true
  try {
    const response = await aiEvaluationApi.dataset()
    dataset.value = response.response || { cases: [] }
  } finally {
    loadingDataset.value = false
  }
}

async function loadRuns(silent = false) {
  if (!silent) loadingRuns.value = true
  try {
    const response = await aiEvaluationApi.runs(50)
    runs.value = response.response || []
  } finally {
    loadingRuns.value = false
  }
}

async function startRun() {
  const count = selectedCases.value.length || modelCases.value.length
  if (!count) {
    ElMessage.warning('没有可执行的模型用例')
    return
  }
  await ElMessageBox.confirm(
    `将调用当前真实模型执行 ${count} 条固定用例，可能产生费用。是否继续？`,
    '确认模型评测',
    { type: 'warning', confirmButtonText: '确认执行', cancelButtonText: '取消' }
  )
  starting.value = true
  try {
    const response = await aiEvaluationApi.start({
      candidateLabel: candidateLabel.value || null,
      caseIds: selectedCases.value.map(item => item.id)
    })
    ElMessage.success(`评测 Run #${response.response.id} 已进入队列`)
    await loadRuns(true)
  } finally {
    starting.value = false
  }
}

async function showDetail(id) {
  const response = await aiEvaluationApi.detail(id)
  detail.value = response.response || { results: [] }
  detailVisible.value = true
}

async function compareRuns() {
  const response = await aiEvaluationApi.compare(baselineRunId.value, candidateRunId.value)
  comparison.value = response.response
}

function statusType(status) {
  return { completed: 'success', failed: 'danger', running: 'warning', queued: 'info' }[status] || 'info'
}

function formatNumber(value, digits) {
  return Number(value || 0).toFixed(digits)
}

function signed(value, digits) {
  const number = Number(value || 0)
  return `${number > 0 ? '+' : ''}${number.toFixed(digits)}`
}

onMounted(async () => {
  await Promise.all([loadDataset(), loadRuns()])
  pollTimer = window.setInterval(() => {
    if (hasActiveRuns.value) loadRuns(true)
  }, 3000)
})

onBeforeUnmount(() => {
  if (pollTimer) window.clearInterval(pollTimer)
})
</script>

<style scoped lang="scss">
.evaluation-page {
  .section-gap { margin-top: 16px; }
  .left-gap { margin-left: 8px; }
  .card-header { display: flex; align-items: center; justify-content: space-between; }
  .description { color: #606266; line-height: 1.6; margin-top: 0; }
  .contract-summary p { color: #909399; line-height: 1.6; }
  .compare-form {
    display: flex;
    align-items: center;
    gap: 12px;
    .el-select { width: 280px; }
  }
}
</style>
