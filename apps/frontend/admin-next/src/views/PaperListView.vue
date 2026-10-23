<template>
  <div class="page-card" v-loading="loading">
    <h3 style="margin-top: 0">候选库试卷（整卷 408 真题）</h3>

    <el-alert
      v-if="loadError"
      :title="loadError"
      type="error"
      :closable="false"
      show-icon
      style="margin-bottom: 16px"
    >
      <el-button size="small" @click="load">重试</el-button>
    </el-alert>

    <el-table v-else :data="papers" border>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="试卷" min-width="180" />
      <el-table-column prop="year" label="年份" width="90" />
      <el-table-column prop="questionCount" label="题数" width="90" />
      <el-table-column label="导入告警" width="110">
        <template #default="{ row }">
          <el-tag :type="row.warningCount > 0 ? 'warning' : 'success'" size="small">
            {{ row.warningCount }} 条
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="open(row.id)">按卷浏览</el-button>
        </template>
      </el-table-column>
    </el-table>

    <p class="muted" style="margin-top: 12px">
      数据为候选库（master408_candidate）本地导入切片；仅词法/结构对账，不代表线上或完整题库。
    </p>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchPapers } from '@/api/papers'

const router = useRouter()
const papers = ref([])
const loading = ref(false)
const loadError = ref('')

function open(id) {
  router.push({ name: 'paper-detail', params: { id } })
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const body = await fetchPapers()
    papers.value = body.response
  } catch (err) {
    loadError.value = err?.message || '试卷列表加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>
