<template>
  <el-container class="admin-layout">
    <el-aside width="200px" class="aside">
      <div class="brand">Master408 管理端<span class="brand-sub">新库</span></div>
      <el-menu :default-active="$route.path" router>
        <el-menu-item index="/">
          <el-icon><Odometer /></el-icon>
          <span>工作台</span>
        </el-menu-item>
        <el-menu-item index="/questions">
          <el-icon><Document /></el-icon>
          <span>题目</span>
        </el-menu-item>
        <el-menu-item index="/papers">
          <el-icon><Document /></el-icon>
          <span>试卷</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span v-if="auth.username" class="user">{{ auth.username }}</span>
        <el-button link type="danger" @click="onLogout">退出</el-button>
      </el-header>
      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { logout } from '@/api/auth'

const auth = useAuthStore()
const router = useRouter()

async function onLogout() {
  try {
    await logout()
  } catch {
    // token 已失效也继续本地清理
  }
  auth.clear()
  ElMessage.success('已退出')
  router.push('/login')
}
</script>

<style scoped>
.admin-layout {
  min-height: 100vh;
}

.aside {
  background: #fff;
  border-right: 1px solid #e4e7ed;
}

.brand {
  padding: 16px;
  font-size: 16px;
  font-weight: 600;
}

.brand-sub {
  margin-left: 6px;
  font-size: 12px;
  color: #909399;
  font-weight: 400;
}

.header {
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
}

.user {
  color: #606266;
}
</style>
