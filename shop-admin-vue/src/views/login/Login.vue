<template>
  <div class="login-container">
    <div class="login-card">
      <div class="header">
        <h2>B2B2C 综合管理运营中台</h2>
        <p>Spring Boot 3 + Vue 3 · 平台超管 & 商家工作台双重视角</p>
      </div>

      <el-form :model="form" @submit.prevent="handleLogin" label-position="top">
        <el-form-item label="登录账号">
          <el-input v-model="form.username" placeholder="请输入用户名/手机号" prefix-icon="User" size="large" />
        </el-form-item>
        <el-form-item label="登录密码">
          <el-input v-model="form.password" type="password" placeholder="请输入密码" prefix-icon="Lock" show-password size="large" />
        </el-form-item>

        <el-button type="primary" :loading="loading" class="submit-btn" size="large" @click="handleLogin">
          立即登录
        </el-button>
      </el-form>

      <div class="quick-fill">
        <div class="fill-title">体验账号一键填入：</div>
        <div class="btn-group">
          <el-button size="small" type="danger" plain @click="quickFill('superadmin', 'admin123')">
            👑 平台超管视角 (superadmin)
          </el-button>
          <el-button size="small" type="primary" plain @click="quickFill('seller01', 'admin123')">
            🏪 商家店长视角 (seller01)
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../../api/request'

const router = useRouter()
const loading = ref(false)

const form = ref({
  username: 'superadmin',
  password: 'admin123'
})

const quickFill = (u, p) => {
  form.value.username = u
  form.value.password = p
}

const handleLogin = async () => {
  if (!form.value.username || !form.value.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const res = await request.post('/auth/login', form.value)
    const { token, roleCode, merchantId, realName, storeName } = res.data

    localStorage.setItem('token', token)
    localStorage.setItem('roleCode', roleCode)
    localStorage.setItem('merchantId', merchantId || '')
    localStorage.setItem('realName', realName)
    localStorage.setItem('storeName', storeName || '')

    ElMessage.success(`欢迎回来，${realName}！`)

    // 根据角色初次跳转
    if (roleCode === 'SUPERADMIN') {
      localStorage.setItem('currentPerspective', 'ADMIN')
      router.push('/admin/dashboard')
    } else {
      localStorage.setItem('currentPerspective', 'MERCHANT')
      router.push('/merchant/dashboard')
    }
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-container {
  height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  background: linear-gradient(135deg, #0f172a 0%, #1e293b 100%);
}

.login-card {
  width: 420px;
  background: #ffffff;
  padding: 40px;
  border-radius: 12px;
  box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.3), 0 10px 10px -5px rgba(0, 0, 0, 0.2);
}

.header h2 {
  margin: 0;
  font-size: 22px;
  color: #1e293b;
  font-weight: 700;
  text-align: center;
}

.header p {
  margin: 8px 0 24px;
  font-size: 13px;
  color: #64748b;
  text-align: center;
}

.submit-btn {
  width: 100%;
  margin-top: 10px;
  background: #2563eb;
  border: none;
}

.quick-fill {
  margin-top: 30px;
  padding-top: 20px;
  border-top: 1px dashed #e2e8f0;
}

.fill-title {
  font-size: 12px;
  color: #94a3b8;
  margin-bottom: 10px;
}

.btn-group {
  display: flex;
  gap: 10px;
}
</style>
