<template>
  <div v-loading="loading">
    <div class="stat-grid">
      <div class="stat-card">
        <div class="muted">题目总数</div>
        <div class="stat-value">{{ stats?.questionTotal ?? '—' }}</div>
      </div>
      <div class="stat-card">
        <div class="muted">导入批次</div>
        <div class="stat-value">{{ stats?.batchTotal ?? '—' }}</div>
      </div>
      <div class="stat-card">
        <div class="muted">最近批次</div>
        <div class="stat-value stat-time">{{ stats?.lastBatchAt ?? '—' }}</div>
      </div>
    </div>

    <el-row :gutter="16">
      <el-col :span="8">
        <div class="page-card">
          <h4>按来源年份</h4>
          <el-table :data="stats?.byYear ?? []" size="small">
            <el-table-column prop="key" label="年份" />
            <el-table-column prop="count" label="题数" />
          </el-table>
        </div>
      </el-col>
      <el-col :span="8">
        <div class="page-card">
          <h4>按题型</h4>
          <el-table :data="typeRows" size="small">
            <el-table-column prop="key" label="题型" />
            <el-table-column prop="count" label="题数" />
          </el-table>
        </div>
      </el-col>
      <el-col :span="8">
        <div class="page-card">
          <h4>按状态</h4>
          <el-table :data="statusRows" size="small">
            <el-table-column prop="key" label="状态" />
            <el-table-column prop="count" label="题数" />
          </el-table>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { fetchStats } from '@/api/questions'

const QUESTION_TYPE_LABELS = { 1: '单选', 2: '多选', 3: '判断', 4: '填空', 5: '简答' }
const STATUS_LABELS = { 1: '正常', 2: '停用', 3: '紧急撤下' }

const stats = ref(null)
const loading = ref(false)

const typeRows = computed(() =>
  (stats.value?.byType ?? []).map((i) => ({ key: QUESTION_TYPE_LABELS[i.key] || i.key, count: i.count }))
)
const statusRows = computed(() =>
  (stats.value?.byStatus ?? []).map((i) => ({ key: STATUS_LABELS[i.key] || i.key, count: i.count }))
)

onMounted(async () => {
  loading.value = true
  try {
    const body = await fetchStats()
    stats.value = body.response
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.stat-time {
  font-size: 15px;
  line-height: 34px;
}
</style>
