<!--
文件：Login.vue
用途：商家登录页（参考拼多多 MMS 布局）与新建店铺入口。
维护注意：
1. 本页只服务商家角色，登录请求必须带 scope=MERCHANT，超管账号会被后端拒绝；平台超管请使用 /admin/login 私密入口，本页不得展示或链接该入口。
2. 图形验证码一次性失效，登录失败或提交后都要刷新；验证码图片不得缓存（后端每次返回新 captchaId）。
3. 本地 token/角色仅供展示与路由跳转，授权仍由后端判定；不得预填演示账号、口令或提供一键登录。
4. “我要开店”进入 /apply 公开向导，不需要登录；申请查询凭证保存在 localStorage，敏感初始密码只在结果页展示一次。
-->
<template>
  <div class="mms-login">
    <div class="brand-panel">
      <div class="brand-logo">B2B2C<span>商家中心</span></div>
      <h1>开店，从这里开始</h1>
      <p class="brand-desc">个人身份证、个体工商户、企业均可入驻，平台人工审核，审核通过自动开通店铺与店长账号。</p>
      <div class="brand-steps">
        <div class="step-item"><span class="step-no">1</span>准备身份证 / 营业执照等资质</div>
        <div class="step-item"><span class="step-no">2</span>在线提交，1-3 个工作日完成人工审核</div>
        <div class="step-item"><span class="step-no">3</span>审核通过后登录后台完成开店设置</div>
      </div>
      <div class="brand-foot">资质信息仅用于平台审核，证件文件存储在平台私有空间。</div>
    </div>

    <div class="form-panel">
      <div class="form-card">
        <div class="form-head">
          <h2>商家登录</h2>
          <p>使用申请时填写的手机号登录后台</p>
        </div>

        <el-form :model="form" label-position="top" @submit.prevent="handleLogin">
          <el-form-item label="手机号 / 账号">
            <el-input v-model="form.username" placeholder="请输入申请手机号" prefix-icon="User" size="large" />
          </el-form-item>
          <el-form-item label="登录密码">
            <el-input v-model="form.password" type="password" placeholder="请输入登录密码" prefix-icon="Lock"
              show-password size="large" @keyup.enter="handleLogin" />
          </el-form-item>
          <el-form-item label="图形验证码">
            <div class="captcha-row">
              <el-input v-model="form.captchaCode" placeholder="请输入右侧验证码" maxlength="5" size="large"
                @keyup.enter="handleLogin" />
              <button type="button" class="captcha-image" :disabled="captchaLoading" title="点击刷新"
                @click="loadCaptcha">
                <img v-if="captchaImage" :src="captchaImage" alt="图形验证码" />
                <span v-else>{{ captchaLoading ? '加载中' : '点击重试' }}</span>
              </button>
            </div>
          </el-form-item>

          <el-button type="primary" :loading="loading" class="submit-btn" size="large" @click="handleLogin">
            立即登录
          </el-button>
        </el-form>

        <div class="form-foot">
          <span>还没有店铺？</span>
          <router-link to="/apply" class="apply-link">我要开店 / 查询审核进度</router-link>
        </div>
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
    ElMessage.warning('请输入手机号和密码')
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
      scope: 'MERCHANT'
    })
    const { token, roleCode, realName } = res.data
    localStorage.setItem('token', token)
    cacheProfile(localStorage, res.data)
    localStorage.setItem('currentPerspective', 'MERCHANT')
    ElMessage.success(`欢迎回来，${realName || '商家'}！`)
    await router.replace(homeFor(res.data))
  } catch {
    // 请求层已提示失败信息；不记录含密码/验证码的请求配置。
    await loadCaptcha()
  } finally {
    loading.value = false
  }
}

onMounted(loadCaptcha)
</script>

<style scoped>
.mms-login {
  min-height: 100vh;
  display: flex;
  background: #f4f5f7;
}

.brand-panel {
  width: 46%;
  max-width: 620px;
  padding: 64px 56px;
  color: #fff;
  background: linear-gradient(160deg, #ff5000 0%, #ff7a2f 55%, #ff9a3d 100%);
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.brand-logo {
  font-size: 26px;
  font-weight: 800;
  letter-spacing: 1px;
}

.brand-logo span {
  font-size: 14px;
  font-weight: 500;
  margin-left: 8px;
  opacity: 0.9;
}

.brand-panel h1 {
  margin: 36px 0 12px;
  font-size: 38px;
  line-height: 1.25;
}

.brand-desc {
  margin: 0 0 36px;
  font-size: 15px;
  line-height: 1.8;
  opacity: 0.94;
}

.brand-steps {
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin-bottom: 40px;
}

.step-item {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 14px;
}

.step-no {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.22);
  border: 1px solid rgba(255, 255, 255, 0.5);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  flex-shrink: 0;
}

.brand-foot {
  font-size: 12px;
  opacity: 0.8;
}

.form-panel {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
}

.form-card {
  width: 100%;
  max-width: 400px;
  background: #fff;
  border-radius: 12px;
  padding: 40px 36px 28px;
  box-shadow: 0 18px 40px rgba(15, 23, 42, 0.08);
}

.form-head h2 {
  margin: 0;
  font-size: 24px;
  color: #1f2937;
}

.form-head p {
  margin: 8px 0 26px;
  font-size: 13px;
  color: #6b7280;
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
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  padding: 0;
  background: #f8fafc;
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
  color: #6b7280;
}

.submit-btn {
  width: 100%;
  margin-top: 4px;
  background: #ff5000;
  border-color: #ff5000;
  font-weight: 600;
}

.submit-btn:hover,
.submit-btn:focus {
  background: #e64800;
  border-color: #e64800;
}

.form-foot {
  margin-top: 22px;
  display: flex;
  justify-content: center;
  gap: 6px;
  font-size: 13px;
  color: #6b7280;
}

.apply-link {
  color: #ff5000;
  font-weight: 600;
  text-decoration: none;
}

@media (max-width: 960px) {
  .brand-panel {
    display: none;
  }
  .form-panel {
    width: 100%;
  }
}
</style>
