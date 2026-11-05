<!--
文件：Login.vue
用途：管理端登录表单和会话信息初始化。
维护注意：本地存储的token/角色不可视为可信授权；登录后按服务端角色与店铺绑定选择首页，再由路由验证身份；不得预填演示账号/口令或提供一键填入；不要在日志中输出密码或令牌，修改响应字段要同步身份接口。
-->
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
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../../api/request'
import { cacheProfile, homeFor } from '../../utils/authSession.mjs'

const router = useRouter()
const loading = ref(false)

const form = ref({
  username: '',
  password: ''
})

const handleLogin = async () => {
  if (!form.value.username || !form.value.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const res = await request.post('/auth/login', form.value)
    const { token, roleCode, realName } = res.data

    localStorage.setItem('token', token)
    cacheProfile(localStorage, res.data)

    ElMessage.success(`欢迎回来，${realName}！`)

    localStorage.setItem('currentPerspective', roleCode === 'SUPERADMIN' ? 'ADMIN' : 'MERCHANT')
    await router.replace(homeFor(res.data))
  } catch (e) {
    // 请求层已显示失败信息，不记录含密码或令牌的Axios配置。
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
</style>
