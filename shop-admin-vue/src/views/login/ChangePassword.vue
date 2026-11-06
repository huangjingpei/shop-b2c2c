<!--
文件：ChangePassword.vue
用途：D11 首次登录强制改密页；标记账号被路由守卫限制在本页，改密成功后重新登录。
维护注意：
1. 本页为独立页面（不在 AdminLayout 内），标记账号无法访问任何业务页，不得在本页渲染店铺或平台私有数据。
2. 前端校验只作体验提示，强度与“不得与原密码相同”等规则以后端 /auth/change-password 判定为准。
3. 改密成功后端会注销该账号全部会话（含当前令牌），本页必须清空本地会话并回到对应登录入口，不得静默沿用旧令牌。
-->
<template>
  <div class="change-password">
    <div class="panel">
      <div class="head">
        <h1>修改初始密码</h1>
        <p>首次登录须先设置新密码，原初始密码改密后立即失效。新密码需 8-128 位且同时包含字母与数字。</p>
      </div>

      <el-form :model="form" label-position="top" @submit.prevent="handleSubmit">
        <el-form-item label="原密码（初始密码）">
          <el-input v-model="form.oldPassword" type="password" placeholder="请输入审核通过时发放的初始密码"
            prefix-icon="Key" show-password size="large" />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="form.newPassword" type="password" placeholder="8-128 位，包含字母与数字"
            prefix-icon="Lock" show-password size="large" maxlength="128" />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="form.confirmPassword" type="password" placeholder="请再次输入新密码"
            prefix-icon="Lock" show-password size="large" maxlength="128" @keyup.enter="handleSubmit" />
        </el-form-item>

        <el-button type="primary" :loading="loading" class="submit-btn" size="large" @click="handleSubmit">
          确认修改并重新登录
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
import { clearSession, loginPathFor } from '../../utils/authSession.mjs'

const router = useRouter()
const loading = ref(false)
const form = ref({ oldPassword: '', newPassword: '', confirmPassword: '' })

const PASSWORD_PATTERN = /(?=.*[A-Za-z])(?=.*\d)[\s\S]+/

const validate = () => {
  if (!form.value.oldPassword) {
    ElMessage.warning('请输入原密码')
    return false
  }
  const next = form.value.newPassword
  if (next.length < 8 || next.length > 128 || !PASSWORD_PATTERN.test(next)) {
    ElMessage.warning('新密码需为 8-128 位且同时包含字母与数字')
    return false
  }
  if (next === form.value.oldPassword) {
    ElMessage.warning('新密码不得与原密码相同')
    return false
  }
  if (next !== form.value.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致')
    return false
  }
  return true
}

const handleSubmit = async () => {
  if (!validate()) return
  loading.value = true
  try {
    await request.post('/auth/change-password', {
      oldPassword: form.value.oldPassword,
      newPassword: form.value.newPassword
    })
    // 后端已注销全部会话；先按视角算入口再清理会话（清理后无法读取 currentPerspective）。
    const loginTarget = loginPathFor(import.meta.env.BASE_URL, localStorage.getItem('currentPerspective'))
    clearSession(localStorage)
    ElMessage.success('密码已修改，请使用新密码重新登录')
    await router.replace(loginTarget)
  } catch {
    // 失败信息由请求层提示；令牌仍然有效，可重试。
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.change-password {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
  background: #f4f5f7;
}

.panel {
  width: 100%;
  max-width: 420px;
  background: #fff;
  border-radius: 12px;
  padding: 40px 36px 32px;
  box-shadow: 0 18px 40px rgba(15, 23, 42, 0.08);
}

.head h1 {
  margin: 0;
  font-size: 22px;
  color: #1f2937;
}

.head p {
  margin: 10px 0 26px;
  font-size: 13px;
  line-height: 1.7;
  color: #6b7280;
}

.submit-btn {
  width: 100%;
  margin-top: 6px;
  background: #ff5000;
  border-color: #ff5000;
  font-weight: 600;
}

.submit-btn:hover,
.submit-btn:focus {
  background: #e64800;
  border-color: #e64800;
}
</style>
