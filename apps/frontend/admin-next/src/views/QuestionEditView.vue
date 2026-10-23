<template>
  <div class="page-card" v-loading="loading">
    <el-page-header :content="`编辑题目 #${id}`" @back="goBack" />

    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false"
      show-icon style="margin-top: 16px">
      <el-button size="small" @click="load">重试</el-button>
    </el-alert>

    <el-alert v-if="conflict" type="warning" :closable="false" show-icon
      :title="conflict" style="margin-top: 16px" />

    <el-alert v-if="savedVersion" type="success" :closable="false" show-icon
      :title="`已保存为新版本 v${savedVersion}`" style="margin-top: 16px" />

    <template v-if="q">
      <el-descriptions :column="3" border style="margin-top: 16px">
        <el-descriptions-item label="题目 ID">{{ q.id }}</el-descriptions-item>
        <el-descriptions-item label="学科">{{ q.subject_name ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="当前版本（expectedVersion）">
          <el-tag size="small">v{{ q.current_version_no }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="人工编辑历史">
          <el-tag :type="q.edited_manual ? 'warning' : 'info'" size="small">
            {{ q.edited_manual ? '有人工编辑（重导不覆盖）' : '无（重导安全）' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="原始来源" :span="2">
          <a v-if="q.source_ref" :href="q.source_ref" target="_blank"
            rel="noopener noreferrer">{{ q.source_ref }}</a>
        </el-descriptions-item>
      </el-descriptions>

      <h4>编辑（保存生成不可变新版本 v{{ (q.current_version_no || 0) + 1 }}）</h4>
      <el-form label-position="top">
        <el-form-item label="题干 HTML（含选项）">
          <el-input v-model="form.stemHtml" type="textarea" :rows="8" />
        </el-form-item>
        <el-form-item label="解析 HTML">
          <el-input v-model="form.analysisHtml" type="textarea" :rows="5" />
        </el-form-item>
        <el-form-item label="答案（单选=字母）">
          <el-input v-model="form.correct" style="width: 200px" />
        </el-form-item>
        <el-button type="primary" :loading="saving" @click="save">
          保存（expectedVersion={{ form.expectedVersion }}）
        </el-button>
      </el-form>

      <h4>版本历史（不可变）</h4>
      <el-table :data="versions" border size="small">
        <el-table-column prop="version_no" label="版本" width="70" />
        <el-table-column prop="edit_source" label="来源" width="110" />
        <el-table-column prop="stem_chars" label="题干字符数" width="110" />
        <el-table-column prop="created_at" label="创建时间" min-width="170" />
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button size="small" link @click="viewVersion(row.version_no)">查看</el-button>
            <el-button size="small" type="warning" link
              @click="rollback(row.version_no)">回滚到此版</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <el-drawer v-model="drawer" :title="`版本 v${viewVno}`" size="55%">
      <QuestionHtml v-if="viewHtml" :html="viewHtml" />
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import QuestionHtml from '@/components/QuestionHtml.vue'
import {
  fetchM2Question, fetchM2QuestionIdByRef, fetchM2Versions,
  fetchM2Version, saveM2Question, rollbackM2Question,
} from '@/api/m2'

const route = useRoute()
const router = useRouter()
const id = ref(null)
const q = ref(null)
const versions = ref([])
const loading = ref(false)
const loadError = ref('')
const conflict = ref('')
const savedVersion = ref(null)
const form = ref({ stemHtml: '', analysisHtml: '', correct: '', expectedVersion: null })
const saving = ref(false)
const drawer = ref(false)
const viewVno = ref(null)
const viewHtml = ref('')

function goBack() {
  router.back()
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    // 支持两种定位：M2 库 id，或跨库一致的 source_ref（候选库行跳转）
    let qid = id.value
    if (!qid && route.query.ref) {
      const refBody = await fetchM2QuestionIdByRef(route.query.ref)
      qid = refBody.response.id
      id.value = qid
    }
    const body = await fetchM2Question(qid)
    q.value = body.response
    form.value = {
      stemHtml: q.value.stem_html || '',
      analysisHtml: q.value.analysis_html || '',
      correct: q.value.correct || '',
      expectedVersion: q.value.current_version_no,
    }
    versions.value = q.value.versions || []
  } catch (err) {
    loadError.value = err?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  conflict.value = ''
  savedVersion.value = null
  try {
    const body = await saveM2Question(id.value, {
      expectedVersion: q.value.current_version_no,
      stemHtml: form.value.stemHtml,
      analysisHtml: form.value.analysisHtml,
      correct: form.value.correct,
      editNote: 'admin-next edit',
    })
    savedVersion.value = body.response.version_no
    ElMessage.success(`已保存为 v${body.response.version_no}（不可变新版本）`)
    await load()
  } catch (err) {
    const msg = err?.message || ''
    if (msg.includes('409') || msg.includes('冲突')) {
      conflict.value = msg + '——请刷新获取最新版本后再编辑（原子保护，未写入）'
    } else {
      conflict.value = msg
    }
  } finally {
    saving.value = false
  }
}

async function rollback(vno) {
  try {
    await ElMessageBox.confirm(
      `回滚到 v${vno} 将生成新版本（历史不改），当前版本变为 v${q.value.current_version_no + 1}。继续？`,
      '回滚确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    const body = await rollbackM2Question(id.value, {
      expectedVersion: q.value.current_version_no,
      toVersion: vno,
      editNote: `rollback to v${vno} from admin-next`,
    })
    ElMessage.success(`已回滚：新版本 v${body.response.version_no}`)
    await load()
  } catch (err) {
    ElMessage.error(err?.message || '回滚失败')
  }
}

async function viewVersion(vno) {
  viewVno.value = vno
  viewHtml.value = ''
  drawer.value = true
  try {
    const body = await fetchM2Version(id.value, vno)
    const parts = [`<h5>题干</h5>${body.response.stem_html || ''}`]
    if (body.response.analysis_html) {
      parts.push(`<h5>解析</h5>${body.response.analysis_html}`)
    }
    viewHtml.value = parts.join('')
  } catch (err) {
    viewHtml.value = `<p>加载失败：${err?.message || ''}</p>`
  }
}

onMounted(load)
</script>
