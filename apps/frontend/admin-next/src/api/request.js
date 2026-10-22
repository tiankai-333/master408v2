import axios from 'axios'
import { useAuthStore } from '@/stores/auth'
import router from '@/router'

const request = axios.create({
  baseURL: '/api',
  timeout: 15000
})

request.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  return config
})

request.interceptors.response.use(
  (response) => {
    const body = response.data
    // 后端信封 {code, message, response}，SystemCode.OK = 1
    if (body && typeof body.code === 'number' && body.code !== 1) {
      return Promise.reject(new Error(body.message || `请求失败（${body.code}）`))
    }
    return body
  },
  (error) => {
    const status = error.response?.status
    if (status === 401) {
      const auth = useAuthStore()
      auth.clear()
      router.push('/login')
      return Promise.reject(new Error('未登录或凭证失效'))
    }
    const body = error.response?.data
    return Promise.reject(new Error(body?.message || `请求失败（${status || 'network'}）`))
  }
)

export default request
