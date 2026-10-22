<template>
  <div class="page-card" v-loading="loading">
    <el-form inline>
      <el-form-item label="关键词">
        <el-input v-model="filters.keyword" placeholder="题干包含…" clearable style="width: 200px" @keyup.enter="search" />
      </el-form-item>
      <el-form-item label="题型">
        <el-select v-model="filters.questionType" clearable placeholder="全部" style="width: 110px">
          <el-option v-for="(label, value) in TYPE_LABELS" :key="value" :label="label" :value="Number(value)" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="filters.status" clearable placeholder="全部" style="width: 130px">
          <el-option v-for="(label, value) in STATUS_LABELS" :key="value" :label="label" :value="Number(value)" />
        </el-select>
      </el-form-item>
      <el-form-item label="来源年份">
        <el-input-number v-model="filters.sourceYear" :min="2000" :max="2100" controls-position="right" placeholder="全部" style="width: 120px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="rows" size="default">
      <el-table-column prop="id" label="ID" width="64" />
      <el-table-column label="题型" width="72">
        <template #default="{ row }">{{ TYPE_LABELS[row.questionType] || row.questionType }}</template>
      </el-table-column>
      <el-table-column prop="subjectName" label="学科" width="110" />
      <el-table-column label="来源" width="170">
        <template #default="{ row }">{{ row.sourceName }}<span class="muted"> 第{{ row.sourceQuestionNo }}题</span></template>
      </el-table-column>
      <el-table-column prop="shortTitle" label="题干" min-width="280" show-overflow-tooltip />
      <el-table-column label="答案" width="72">
        <template #default="{ row }">{{ row.correct ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)" size="small">{{ STATUS_LABELS[row.status] || row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="上线阀" width="88">
        <template #default="{ row }">
          <el-tag :type="row.displayReady === 1 ? 'success' : 'info'" size="small">
            {{ row.displayReady === 1 ? '就绪' : '未就绪' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="licenseTag" label="授权" width="130" />
      <el-table-column label="操作" width="80" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="$router.push(`/questions/${row.id}`)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="page.pageIndex"
      v-model:page-size="page.pageSize"
      :total="total"
      :page-sizes="[10, 20, 50]"
      layout="total, sizes, prev, pager, next"
      style="margin-top: 12px; justify-content: flex-end"
      @current-change="load"
      @size-change="search"
    />
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { fetchQuestionPage } from '@/api/questions'

const TYPE_LABELS = { 1: '单选', 2: '多选', 3: '判断', 4: '填空', 5: '简答' }
const STATUS_LABELS = { 1: '正常', 2: '停用', 3: '紧急撤下' }

const filters = reactive({ keyword: '', questionType: null, status: null, sourceYear: null })
const page = reactive({ pageIndex: 1, pageSize: 10 })
const rows = ref([])
const total = ref(0)
const loading = ref(false)

function statusTagType(status) {
  if (status === 1) return 'success'
  if (status === 2) return 'warning'
  return 'danger'
}

async function load() {
  loading.value = true
  try {
    const body = await fetchQuestionPage({
      pageIndex: page.pageIndex,
      pageSize: page.pageSize,
      keyword: filters.keyword || null,
      questionType: filters.questionType,
      status: filters.status,
      sourceYear: filters.sourceYear
    })
    rows.value = body.response.list
    total.value = body.response.total
  } finally {
    loading.value = false
  }
}

function search() {
  page.pageIndex = 1
  load()
}

function reset() {
  filters.keyword = ''
  filters.questionType = null
  filters.status = null
  filters.sourceYear = null
  search()
}

onMounted(load)
</script>
