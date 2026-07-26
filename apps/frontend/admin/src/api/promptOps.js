import { post, get } from '@/utils/request'

const base = '/api/admin/prompt-ops'

export default {
  definitions: () => get(`${base}/definitions`),
  definitionDetail: key => get(`${base}/definitions/${key}`),
  version: id => get(`${base}/versions/${id}`),
  diff: (id, against) => get(`${base}/versions/${id}/diff?against=${against}`),
  createDraft: (key, body) => post(`${base}/definitions/${key}/versions`, body || {}),
  editDraft: (id, body) => post(`${base}/versions/${id}`, body),
  test: (id, body) => post(`${base}/versions/${id}/test`, body),
  submit: id => post(`${base}/versions/${id}/submit`),
  approve: (id, body) => post(`${base}/versions/${id}/approve`, body),
  canary: (id, body) => post(`${base}/versions/${id}/canary`, body),
  promote: id => post(`${base}/versions/${id}/promote`),
  rollback: (key, body) => post(`${base}/definitions/${key}/rollback`, body),
  killSwitch: (key, body) => post(`${base}/definitions/${key}/kill-switch`, body)
}
