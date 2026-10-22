import { defineStore } from 'pinia'

const STORAGE_KEY = 'admin-next-auth'

function restore() {
  try {
    return JSON.parse(sessionStorage.getItem(STORAGE_KEY)) || {}
  } catch {
    return {}
  }
}

/**
 * 登录态：token 内存 + sessionStorage（刷新恢复）。
 * 后端 token 本身重启即失效（预期），401 时由 request 拦截器统一清态回登录页。
 */
export const useAuthStore = defineStore('auth', {
  state: () => {
    const saved = restore()
    return {
      token: saved.token || '',
      username: saved.username || '',
      expiresAt: saved.expiresAt || null
    }
  },
  getters: {
    isLoggedIn: (state) => Boolean(state.token)
  },
  actions: {
    setSession({ token, username, expiresAt }) {
      this.token = token
      this.username = username
      this.expiresAt = expiresAt
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ token, username, expiresAt }))
    },
    clear() {
      this.token = ''
      this.username = ''
      this.expiresAt = null
      sessionStorage.removeItem(STORAGE_KEY)
    }
  }
})
