import { get, post } from '@/utils/request'

const base = '/api/admin/ai-evaluation'

export default {
  dataset: () => get(`${base}/dataset`),
  start: body => post(`${base}/runs`, body),
  runs: (limit = 20) => get(`${base}/runs?limit=${limit}`),
  detail: id => get(`${base}/runs/${id}`),
  compare: (baselineRunId, candidateRunId) =>
    get(`${base}/compare?baselineRunId=${baselineRunId}&candidateRunId=${candidateRunId}`)
}
