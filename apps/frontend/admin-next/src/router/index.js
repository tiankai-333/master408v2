import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true }
  },
  {
    path: '/',
    component: () => import('@/layout/AdminLayout.vue'),
    children: [
      { path: '', name: 'workbench', component: () => import('@/views/WorkbenchView.vue') },
      { path: 'questions', name: 'questions', component: () => import('@/views/QuestionListView.vue') },
      { path: 'questions/:id', name: 'question-detail', component: () => import('@/views/QuestionDetailView.vue') },
      { path: 'papers', name: 'papers', component: () => import('@/views/PaperListView.vue') },
      { path: 'papers/:id', name: 'paper-detail', component: () => import('@/views/PaperDetailView.vue') }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 守卫仅判“有无 token”：后端 token 过期由 401 拦截统一处理
router.beforeEach((to) => {
  if (to.meta.public) return true
  const auth = useAuthStore()
  if (!auth.isLoggedIn) return { name: 'login', query: { redirect: to.fullPath } }
  return true
})

export default router
