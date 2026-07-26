<template>
  <div class="app-container prompt-studio">
    <el-row :gutter="16">
      <!-- 左：definition 列表 -->
      <el-col :span="7">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>Prompt 定义</span>
              <el-button type="primary" size="small" :disabled="!currentKey" @click="openCreateDraft">新建草稿</el-button>
            </div>
          </template>
          <el-table
            v-loading="listLoading"
            :data="definitions"
            size="small"
            border
            fit
            highlight-current-row
            @row-click="selectDefinition"
            style="cursor: pointer"
          >
            <el-table-column label="Key / 名称" min-width="160">
              <template #default="{ row }">
                <div class="def-key">{{ row.promptKey }}</div>
                <div class="def-name">{{ row.name }}</div>
              </template>
            </el-table-column>
            <el-table-column label="版本" width="80" align="center">
              <template #default="{ row }">
                <div>v{{ row.activeVersionNo ?? '—' }}</div>
                <el-tag v-if="row.canaryVersionNo" type="warning" size="small" effect="plain" style="margin-top:2px">
                  canary v{{ row.canaryVersionNo }} · {{ row.canaryPercent }}%
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <!-- 右：详情 -->
      <el-col :span="17">
        <el-card shadow="never" v-loading="detailLoading">
          <template #header>
            <div class="card-header">
              <span>{{ currentKey || '请选择左侧 Prompt' }}</span>
              <el-tag v-if="releaseStatus" :type="releaseStatus === 'disabled' ? 'danger' : 'success'" size="small">
                发布：{{ releaseStatus }}
              </el-tag>
            </div>
          </template>

          <el-empty v-if="!currentKey" description="选择左侧任意 Prompt 查看版本与发布" />

          <el-tabs v-else v-model="activeTab">
            <!-- 版本 -->
            <el-tab-pane label="版本" name="versions">
              <el-table :data="detail.versions" size="small" border fit>
                <el-table-column label="版本" prop="versionNo" width="70" align="center">
                  <template #default="{ row }">v{{ row.versionNo }}</template>
                </el-table-column>
                <el-table-column label="状态" width="130" align="center">
                  <template #default="{ row }">
                    <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="变更原因" prop="changeReason" min-width="180" show-overflow-tooltip />
                <el-table-column label="创建时间" width="170">
                  <template #default="{ row }">{{ formatTime(row.createdTime) }}</template>
                </el-table-column>
                <el-table-column label="操作" width="320" align="center">
                  <template #default="{ row }">
                    <el-button v-if="canEdit(row.status)" size="small" @click.stop="openEdit(row)">编辑</el-button>
                    <el-button size="small" @click.stop="openTest(row)">测试</el-button>
                    <el-button v-if="canEdit(row.status)" size="small" type="primary" plain @click.stop="doSubmit(row)">提交审批</el-button>
                    <el-button v-if="row.status === 'awaiting_approval'" size="small" type="success" @click.stop="openApprove(row)">审批</el-button>
                    <el-button v-if="row.status === 'canary'" size="small" type="success" @click.stop="doPromote(row)">提升发布</el-button>
                    <el-button v-if="row.status !== 'active'" size="small" @click.stop="openDiff(row)">Diff</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>

            <!-- 发布 -->
            <el-tab-pane label="发布" name="release">
              <div v-if="!detail.release" class="empty-tip">该 Prompt 暂无发布记录。</div>
              <div v-else class="release-pane">
                <div class="release-row">
                  <span class="label">稳定版本：</span>
                  <span>v{{ activeVersionNo }} (id {{ detail.release.stableVersionId }})</span>
                </div>
                <div class="release-row">
                  <span class="label">灰度版本：</span>
                  <span v-if="detail.release.canaryVersionId">v{{ canaryVersionNo }} · {{ detail.release.canaryPercent }}%</span>
                  <span v-else class="muted">无灰度</span>
                </div>
                <div class="release-row" v-if="detail.release.canaryVersionId">
                  <span class="label">灰度比例：</span>
                  <el-slider v-model="canaryPercentModel" :min="0" :max="100" :step="5" style="width:240px; margin-right:12px" />
                  <el-button size="small" @click="doCanary(detail.release.canaryVersionId)">应用</el-button>
                </div>
                <div class="release-row">
                  <span class="label">Kill Switch：</span>
                  <el-switch
                    :model-value="detail.release.status === 'active'"
                    active-text="服务中"
                    inactive-text="已冻结"
                    @change="val => doKillSwitch(val)"
                  />
                </div>
                <div class="release-row">
                  <span class="label">回滚到：</span>
                  <el-select v-model="rollbackTarget" placeholder="选择历史版本" size="small" style="width:220px">
                    <el-option
                      v-for="v in rollbackCandidates"
                      :key="v.id"
                      :label="`v${v.versionNo} (${statusLabel(v.status)})`"
                      :value="v.id"
                    />
                  </el-select>
                  <el-button size="small" type="danger" plain :disabled="!rollbackTarget" @click="doRollback">回滚</el-button>
                </div>
              </div>
            </el-tab-pane>

            <!-- 审计 -->
            <el-tab-pane label="审计" name="audit">
              <el-table :data="detail.auditLogs" size="small" border fit>
                <el-table-column label="时间" width="170">
                  <template #default="{ row }">{{ formatTime(row.operateTime) }}</template>
                </el-table-column>
                <el-table-column label="操作" prop="action" width="120" />
                <el-table-column label="操作人" prop="operatorName" width="120" />
                <el-table-column label="版本" width="150">
                  <template #default="{ row }">
                    <span v-if="row.fromVersionId || row.toVersionId">
                      {{ row.fromVersionId ?? '—' }} → {{ row.toVersionId ?? '—' }}
                    </span>
                  </template>
                </el-table-column>
                <el-table-column label="详情/备注" prop="detailJson" min-width="180" show-overflow-tooltip />
              </el-table>
            </el-tab-pane>
          </el-tabs>
        </el-card>
      </el-col>
    </el-row>

    <!-- 草稿编辑 -->
    <el-dialog v-model="editor.visible" :title="editor.versionId ? '编辑草稿' : '新建草稿'" width="780px" :close-on-click-modal="false">
      <el-form label-width="110px">
        <el-form-item label="System Prompt">
          <el-input v-model="editor.form.systemPrompt" type="textarea" :rows="7" placeholder="留空则克隆当前 active 版本" />
        </el-form-item>
        <el-form-item label="User 模板">
          <el-input v-model="editor.form.userPromptTemplate" type="textarea" :rows="5" placeholder="支持 {question} {knowledge_points} {reference_docs}" />
        </el-form-item>
        <el-form-item label="变量 JSON">
          <el-input v-model="editor.form.variablesJson" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="变更原因">
          <el-input v-model="editor.form.changeReason" />
        </el-form-item>
        <el-form-item label="风险说明">
          <el-input v-model="editor.form.riskNote" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editor.visible = false">取消</el-button>
        <el-button type="primary" :loading="editor.saving" @click="saveDraft">保存草稿</el-button>
      </template>
    </el-dialog>

    <!-- 审批 -->
    <el-dialog v-model="approveDlg.visible" title="审批并开始灰度" width="420px">
      <el-form label-width="90px">
        <el-form-item label="灰度比例">
          <el-input-number v-model="approveDlg.percent" :min="0" :max="100" /> %
        </el-form-item>
        <el-form-item label="审批备注">
          <el-input v-model="approveDlg.comment" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="approveDlg.visible = false">取消</el-button>
        <el-button type="success" :loading="approveDlg.saving" @click="doApprove">通过</el-button>
      </template>
    </el-dialog>

    <!-- Playground 测试 -->
    <el-dialog v-model="testDlg.visible" title="Playground（用该版本一次性调用，不影响线上）" width="720px">
      <el-form label-width="90px">
        <el-form-item label="问题">
          <el-input v-model="testDlg.form.question" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="知识点">
          <el-input v-model="testDlg.form.knowledgePoints" />
        </el-form-item>
        <el-form-item label="响应">
          <el-input v-model="testDlg.result" type="textarea" :rows="8" readonly placeholder="点击运行后展示模型响应" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="testDlg.visible = false">关闭</el-button>
        <el-button type="primary" :loading="testDlg.loading" @click="runTest">运行</el-button>
      </template>
    </el-dialog>

    <!-- Diff -->
    <el-dialog v-model="diffDlg.visible" title="版本 Diff（与当前 active）" width="820px">
      <div v-for="sec in [{k:'system', t:'System Prompt'}, {k:'user', t:'User 模板'}]" :key="sec.k" style="margin-bottom:14px">
        <div class="diff-section-title">{{ sec.t }}</div>
        <pre class="diff-block"><template v-for="(line, i) in diffDlg.result[sec.k] || []" :key="i"><span
  :class="['diff-line', 'diff-' + line.type]">{{ line.type === 'add' ? '+ ' : line.type === 'remove' ? '- ' : '  ' }}{{ line.text }}
</span></template></pre>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import promptOpsApi from '@/api/promptOps'

const definitions = ref([])
const currentKey = ref('')
const activeTab = ref('versions')
const listLoading = ref(false)
const detailLoading = ref(false)

const detail = reactive({ definition: null, release: null, versions: [], auditLogs: [] })

const editor = reactive({ visible: false, versionId: null, saving: false, form: emptyForm() })
const approveDlg = reactive({ visible: false, versionId: null, percent: 10, comment: '', saving: false })
const testDlg = reactive({ visible: false, versionId: null, loading: false, result: '', form: { question: '', knowledgePoints: '', referenceDocs: '' } })
const diffDlg = reactive({ visible: false, result: { system: [], user: [] } })

const rollbackTarget = ref(null)
const canaryPercentModel = ref(0)

const releaseStatus = computed(() => detail.release?.status || '')
const activeVersionNo = computed(() => {
  const id = detail.release?.stableVersionId
  const v = detail.versions.find(x => x.id === id)
  return v ? v.versionNo : '—'
})
const canaryVersionNo = computed(() => {
  const id = detail.release?.canaryVersionId
  const v = detail.versions.find(x => x.id === id)
  return v ? v.versionNo : '—'
})
const rollbackCandidates = computed(() => detail.versions.filter(v => v.status !== 'draft' && v.status !== 'testing' && v.status !== 'rejected'))

function emptyForm () {
  return { systemPrompt: '', userPromptTemplate: '', variablesJson: '', modelParamsJson: '', changeReason: '', riskNote: '' }
}

onMounted(loadDefinitions)

async function loadDefinitions () {
  listLoading.value = true
  try {
    const re = await promptOpsApi.definitions()
    definitions.value = re.response || []
    if (definitions.value.length && !currentKey.value) {
      await selectDefinition(definitions.value[0])
    }
  } catch (e) { /* request.js 已提示 */ } finally { listLoading.value = false }
}

async function selectDefinition (row) {
  if (!row || !row.promptKey) return
  currentKey.value = row.promptKey
  activeTab.value = 'versions'
  rollbackTarget.value = null
  detailLoading.value = true
  try {
    const re = await promptOpsApi.definitionDetail(row.promptKey)
    Object.assign(detail, re.response || {})
    canaryPercentModel.value = detail.release?.canaryPercent || 0
  } catch (e) { /* */ } finally { detailLoading.value = false }
}

function canEdit (status) { return ['draft', 'testing', 'rejected'].includes(status) }

function openCreateDraft () {
  editor.versionId = null
  editor.form = emptyForm()
  editor.visible = true
}

function openEdit (row) {
  editor.versionId = row.id
  editor.form = {
    systemPrompt: row.systemPrompt || '',
    userPromptTemplate: row.userPromptTemplate || '',
    variablesJson: row.variablesJson || '',
    modelParamsJson: row.modelParamsJson || '',
    changeReason: row.changeReason || '',
    riskNote: row.riskNote || ''
  }
  editor.visible = true
}

async function saveDraft () {
  editor.saving.value = true
  try {
    if (editor.versionId) {
      await promptOpsApi.editDraft(editor.versionId, editor.form)
    } else {
      await promptOpsApi.createDraft(currentKey.value, editor.form)
    }
    ElMessage.success('已保存')
    editor.visible = false
    await selectDefinition({ promptKey: currentKey.value })
  } catch (e) { /* */ } finally { editor.saving.value = false }
}

async function doSubmit (row) {
  await promptOpsApi.submit(row.id)
  ElMessage.success('已提交审批')
  await selectDefinition({ promptKey: currentKey.value })
}

function openApprove (row) {
  approveDlg.versionId = row.id
  approveDlg.percent = 10
  approveDlg.comment = ''
  approveDlg.visible = true
}

async function doApprove () {
  approveDlg.saving = true
  try {
    await promptOpsApi.approve(approveDlg.versionId, { percent: approveDlg.percent, comment: approveDlg.comment })
    ElMessage.success('已审批，进入灰度')
    approveDlg.visible = false
    await selectDefinition({ promptKey: currentKey.value })
  } catch (e) { /**/ } finally { approveDlg.saving.value = false }
}

async function doCanary (versionId) {
  await promptOpsApi.canary(versionId, { percent: canaryPercentModel.value })
  ElMessage.success('灰度比例已更新')
  await selectDefinition({ promptKey: currentKey.value })
}

async function doPromote (row) {
  await ElMessageBox.confirm(`将 v${row.versionNo} 提升为 active，旧 active 将退休？`, '确认', { type: 'warning' }).catch(() => { throw new Error('cancel') })
  await promptOpsApi.promote(row.id)
  ElMessage.success('已提升为 active')
  await selectDefinition({ promptKey: currentKey.value })
}

async function doRollback () {
  const v = detail.versions.find(x => x.id === rollbackTarget.value)
  await ElMessageBox.confirm(`回滚到 v${v.versionNo}？`, '确认', { type: 'warning' }).catch(() => { throw new Error('cancel') })
  await promptOpsApi.rollback(currentKey.value, { toVersionId: rollbackTarget.value })
  ElMessage.success('已回滚')
  rollbackTarget.value = null
  await selectDefinition({ promptKey: currentKey.value })
}

async function doKillSwitch (enabled) {
  await promptOpsApi.killSwitch(currentKey.value, { enabled })
  ElMessage.success(enabled ? '已恢复服务' : '已冻结灰度（Kill Switch）')
  await selectDefinition({ promptKey: currentKey.value })
}

function openTest (row) {
  testDlg.versionId = row.id
  testDlg.result = ''
  testDlg.form = { question: '', knowledgePoints: '', referenceDocs: '' }
  testDlg.visible = true
}

async function runTest () {
  if (!testDlg.form.question) { ElMessage.warning('请输入问题'); return }
  testDlg.loading = true
  try {
    const re = await promptOpsApi.test(testDlg.versionId, testDlg.form)
    testDlg.result = re.response || ''
  } catch (e) { /**/ } finally { testDlg.loading.value = false }
}

async function openDiff (row) {
  const active = detail.versions.find(x => x.id === detail.release?.stableVersionId)
  if (!active) { ElMessage.warning('找不到 active 版本'); return }
  const re = await promptOpsApi.diff(row.id, active.id)
  diffDlg.result = re.response || { system: [], user: [] }
  diffDlg.visible = true
}

function statusType (s) {
  return { draft: 'info', testing: 'warning', awaiting_approval: 'warning', rejected: 'danger', canary: 'primary', active: 'success', retired: 'info' }[s] || 'info'
}
function statusLabel (s) {
  return { draft: '草稿', testing: '测试', awaiting_approval: '待审批', rejected: '已驳回', canary: '灰度', active: '已发布', retired: '已退役' }[s] || s
}
function formatTime (t) {
  if (!t) return ''
  return String(t).replace('T', ' ').replace(/\..*/, '')
}
</script>

<style scoped>
.prompt-studio .card-header { display: flex; justify-content: space-between; align-items: center; }
.prompt-studio .def-key { font-weight: 600; font-size: 13px; }
.prompt-studio .def-name { font-size: 12px; color: #909399; }
.prompt-studio .empty-tip { color: #909399; padding: 12px 0; }
.prompt-studio .release-pane .release-row { margin-bottom: 14px; display: flex; align-items: center; }
.prompt-studio .release-pane .label { width: 90px; color: #606266; }
.prompt-studio .muted { color: #c0c4cc; }
.prompt-studio .diff-section-title { font-weight: 600; margin: 8px 0 4px; }
.prompt-studio .diff-block { background: #f6f8fa; padding: 8px; border-radius: 4px; font-size: 12px; line-height: 1.6; max-height: 240px; overflow: auto; white-space: pre-wrap; }
.prompt-studio .diff-line { display: block; }
.prompt-studio .diff-add { color: #22863a; background: #e6ffed; }
.prompt-studio .diff-remove { color: #cb2431; background: #ffeef0; }
.prompt-studio .diff-context { color: #6a737d; }
</style>
