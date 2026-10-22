import request from './request'

export function fetchStats() {
  return request.get('/newdb/stats')
}

export function fetchQuestionPage(params) {
  return request.post('/newdb/question/page', params)
}

export function fetchQuestionDetail(id) {
  return request.get(`/newdb/question/${id}`)
}
