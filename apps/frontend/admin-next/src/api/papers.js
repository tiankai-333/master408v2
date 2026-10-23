import request from './request'

export function fetchPapers() {
  return request.get('/newdb/paper/list')
}

export function fetchPaperDetail(id) {
  return request.get(`/newdb/paper/${id}`)
}

export function fetchPaperQuestion(paperId, no) {
  return request.get(`/newdb/paper/${paperId}/question/${no}`)
}
