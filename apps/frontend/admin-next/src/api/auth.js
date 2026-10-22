import request from './request'

export function login(username, password) {
  return request.post('/newdb/auth/login', { username, password })
}

export function logout() {
  return request.post('/newdb/auth/logout')
}

export function me() {
  return request.get('/newdb/auth/me')
}
