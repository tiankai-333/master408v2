<template>
  <div class="m2-page">
    <el-card v-loading="loading">
      <h3>M2 新题库试卷（版本绑定作答）</h3>
      <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />
      <el-table v-else :data="papers" border>
        <el-table-column prop="name" label="试卷" min-width="180" />
        <el-table-column prop="source_year" label="年份" width="90" />
        <el-table-column prop="question_count" label="题数" width="90" />
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="start(row.id)">开始作答</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-if="group" style="margin-top: 16px">
      <h4>答题（绑定版本作答；交卷后回看）</h4>
      <el-alert type="info" :closable="false"
        :title="`绑定组 ${group.slice(0, 12)}…（作答期间管理员编辑不影响本组题面）`" />
      <div v-for="(q, i) in answers" :key="q.question_id" class="q-block">
        <h5>第 {{ i + 1 }} 题（{{ q.subject_name || '' }}）</h5>
        <div v-if="q.stem_html" class="rich" v-html="q.stem_html"></div>
        <el-input v-model="q.my_answer" type="textarea" :rows="2"
          placeholder="作答（综合题将标待人工判分）" style="margin-top: 8px" />
      </div>
      <el-button type="primary" style="margin-top: 12px" :loading="submitting"
        @click="submit">交卷</el-button>
    </el-card>

    <el-card v-if="review" style="margin-top: 16px">
      <h4>回看（绑定版本；单选含答案/解析，综合题待人工判分）</h4>
      <div v-for="(q, i) in review.questions" :key="i" class="q-block">
        <h5>第 {{ i + 1 }} 题 · {{ q.subject_name || '' }}
          <el-tag v-if="q.status === 'submitted'" size="small">已交卷</el-tag>
          <el-tag v-if="q.pending_manual" type="warning" size="small">待人工判分</el-tag>
        </h5>
        <div v-if="q.stem_html" class="rich" v-html="q.stem_html"></div>
        <p>我的作答：{{ q.answer_text || '（未作答）' }}</p>
        <template v-if="q.status === 'submitted' && q.question_type === 1">
          <p>答案：{{ q.correct }}</p>
          <QuestionHtml v-if="q.analysis_html" :html="`<b>解析</b>` + q.analysis_html" />
        </template>
        <el-alert v-else-if="q.status === 'submitted'" type="info" :closable="false"
          title="综合题：已保存作答，待人工判分" style="margin-top: 6px" />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { get, post } from '@/utils/request'
import { ElMessage } from 'element-plus'

const papers = ref([])
const loading = ref(false)
const loadError = ref('')
const group = ref('')
const answers = ref([])
const review = ref(null)
const submitting = ref(false)

async function loadPapers() {
  loading.value = true
  try {
    const r = await get("/api/student/m2/papers")
    papers.value = r.response
  } catch (e) {
    loadError.value = e?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

async function start(paperId) {
  const r = await post("/api/student/m2/answer/start", { paperId })
  const resp = r.response
  group.value = resp.bind_group
  answers.value = resp.questions.map(q => ({ ...q, my_answer: '' }))
  review.value = null
}

async function submit() {
  submitting.value = true
  try {
    const payload = {}
    answers.value.forEach(q => { payload[String(q.question_id)] = q.my_answer })
    await post(`/api/student/m2/answer/group/${group.value}/submit`,
      { answers: payload })
    // 交卷后加载回看
    const r = await get(`/api/student/m2/review/group/${group.value}`)
    review.value = r.response
  } catch (e) {
    ElMessage.error(e?.message || '交卷失败')
  } finally {
    submitting.value = false
  }
}

loadPapers()
</script>

<style scoped>
.m2-page { max-width: 1000px; margin: 0 auto; }
.q-block { border-top: 1px dashed #ddd; padding-top: 8px; margin-top: 12px; }
</style>

<style scoped>
.rich :deep(svg) { max-width: 100%; height: auto; }
.rich { overflow-x: auto; }
</style>
