import request from './request'

export function fetchM2QuestionIdByRef(ref) {
  return request.get('/newdb/m2/question/by-ref', { params: { ref } })
}

export function fetchM2Question(id) {
  return request.get(`/newdb/m2/question/${id}`)
}

export function fetchM2Versions(id) {
  return request.get(`/newdb/m2/question/${id}/versions`)
}

export function fetchM2Version(id, vno) {
  return request.get(`/newdb/m2/question/${id}/version/${vno}`)
}

export function saveM2Question(id, payload) {
  return request.put(`/newdb/m2/question/${id}`, payload)
}

export function rollbackM2Question(id, payload) {
  return request.post(`/newdb/m2/question/${id}/rollback`, payload)
}

export function startM2Answer(id) {
  return request.post('/newdb/m2/answer/start', { questionId: id })
}

export function fetchM2Answer(token) {
  return request.get(`/newdb/m2/answer/${token}`)
}
