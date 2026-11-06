<!--
文件：AdminLogin.vue
用途：平台超级管理员私密登录页（/admin/login），深色专业风格，不提供注册入口。
维护注意：
1. 本入口不对商家公开，商家登录页不得链接或提示此地址；登录请求必须带 scope=ADMIN，非 SUPERADMIN 角色由后端直接拒绝。
2. 图形验证码一次性失效，失败后刷新；不得预填账号、口令或提供演示登录。
3. 仅用于平台运营人员；页面不展示店铺数据、商户列表或任何业务信息。
-->
<template>
  <div class="admin-login">
    <div class="grid-bg"></div>
    <div class="login-box">
      <div class="brand">
        <div class="mark">B2B2C</div>
        <h1>平台管理中心</h1>
        <p>PLATFORM OPERATIONS CONSOLE</p>
      </div>

      <el-form :model="form" label-position="top" class="form" @submit.prevent="handleLogin">
        <el-form-item label="管理员账号">
          <el-input v-model="form.username" placeholder="请输入平台管理员账号" prefix-icon="User" size="large" />
        </el-form-item>
        <el-form-item label="登录密码">
          <el-input v-model="form.password" type="password" placeholder="请输入密码" prefix-icon="Lock"
            show-password size="large" @keyup.enter="handleLogin" />
        </el-form-item>
        <el-form-item label="图形验证码">
          <div class="captcha-row">
            <el-input v-model="form.captchaCode" placeholder="验证码" maxlength="5" size="large"
              @keyup.enter="handleLogin" />
            <button type="button" class="captcha-image" :disabled="captchaLoading" title="点击刷新"
              @click="loadCaptcha">
              <img v-if="captchaImage" :src="captchaImage" alt="图形验证码" />
              <span v-else>{{ captchaLoading ? '加载中' : '点击重试' }}</span>
            </button>
          </div>
        </el-form-item>

        <el-button :loading="loading" class="submit-btn" size="large" @click="handleLogin">
          进入平台管理中心
        </el-button>
      </el-form>

      <div class="foot-note">
        本入口仅限平台运营人员 · 商家请前往
        <router-link to="/login">商家登录</router-link>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../../api/request'
import { cacheProfile, homeFor } from '../../utils/authSession.mjs'

const router = useRouter()
const loading = ref(false)
const captchaLoading = ref(false)
const captchaImage = ref('')
const form = ref({
  username: '',
  password: '',
  captchaId: '',
  captchaCode: ''
})

const loadCaptcha = async () => {
  captchaLoading.value = true
  try {
    const res = await request.get('/auth/captcha', { skipErrorToast: true, skipAuthRedirect: true })
    captchaImage.value = res.data?.image || ''
    form.value.captchaId = res.data?.captchaId || ''
    form.value.captchaCode = ''
  } catch {
    captchaImage.value = ''
    form.value.captchaId = ''
    ElMessage.warning('验证码加载失败，请点击重试')
  } finally {
    captchaLoading.value = false
  }
}

const handleLogin = async () => {
  if (!form.value.username.trim() || !form.value.password) {
    ElMessage.warning('请输入管理员账号和密码')
    return
  }
  if (!form.value.captchaCode.trim()) {
    ElMessage.warning('请输入图形验证码')
    return
  }
  loading.value = true
  try {
    const res = await request.post('/auth/login', {
      username: form.value.username,
      password: form.value.password,
      captchaId: form.value.captchaId,
      captchaCode: form.value.captchaCode,
      scope: 'ADMIN'
    })
    localStorage.setItem('token', res.data.token)
    cacheProfile(localStorage, res.data)
    localStorage.setItem('currentPerspective', 'ADMIN')
    ElMessage.success(`欢迎回来，${res.data.realName || '管理员'}！`)
    await router.replace(homeFor(res.data))
  } catch {
    await loadCaptcha()
  } finally {
    loading.value = false
  }
}

onMounted(loadCaptcha)
</script>

<style scoped>
.admin-login {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: radial-gradient(circle at 20% 15%, #16213e 0%, #0b1120 55%, #060a14 100%);
  overflow: hidden;
}

.grid-bg {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(56, 189, 248, 0.06) 1px, transparent 1px),
    linear-gradient(90deg, rgba(56, 189, 248, 0.06) 1px, transparent 1px);
  background-size: 48px 48px;
  mask-image: radial-gradient(circle at center, #000 20%, transparent 72%);
}

.login-box {
  position: relative;
  width: 100%;
  max-width: 420px;
  padding: 46px 40px 30px;
  border: 1px solid rgba(56, 189, 248, 0.22);
  border-radius: 14px;
  background: rgba(15, 23, 42, 0.82);
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.55), inset 0 1px 0 rgba(255, 255, 255, 0.04);
  backdrop-filter: blur(10px);
  color: #e2e8f0;
}

.brand {
  text-align: center;
  margin-bottom: 28px;
}

.mark {
  display: inline-block;
  padding: 6px 14px;
  border-radius: 999px;
  border: 1px solid rgba(56, 189, 248, 0.45);
  color: #38bdf8;
  font-size: 12px;
  letter-spacing: 3px;
  font-weight: 700;
}

.brand h1 {
  margin: 16px 0 6px;
  font-size: 22px;
  letter-spacing: 4px;
  color: #f1f5f9;
}

.brand p {
  margin: 0;
  font-size: 11px;
  letter-spacing: 2px;
  color: #64748b;
}

.form :deep(.el-form-item__label) {
  color: #94a3b8;
  font-size: 12px;
}

.form :deep(.el-input__wrapper) {
  background: rgba(2, 6, 23, 0.72);
  box-shadow: 0 0 0 1px rgba(71, 85, 105, 0.55) inset;
}

.form :deep(.el-input__inner) {
  color: #e2e8f0;
}

.form :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px #38bdf8 inset;
}

.captcha-row {
  display: flex;
  gap: 10px;
  width: 100%;
}

.captcha-row .el-input {
  flex: 1;
}

.captcha-image {
  width: 132px;
  height: 40px;
  border: 1px solid rgba(71, 85, 105, 0.6);
  border-radius: 6px;
  background: #0f172a;
  padding: 0;
  cursor: pointer;
  overflow: hidden;
  flex-shrink: 0;
}

.captcha-image img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.captcha-image span {
  font-size: 12px;
  color: #94a3b8;
}

.submit-btn {
  width: 100%;
  margin-top: 6px;
  border: none;
  color: #03121f;
  font-weight: 700;
  background: linear-gradient(90deg, #38bdf8, #0ea5e9);
}

.submit-btn:hover,
.submit-btn:focus {
  background: linear-gradient(90deg, #7dd3fc, #38bdf8);
}

.foot-note {
  margin-top: 24px;
  text-align: center;
  font-size: 12px;
  color: #64748b;
}

.foot-note a {
  color: #38bdf8;
  text-decoration: none;
}
</style>
