<!--
文件：ApplyWizard.vue
用途：公开开店向导（/apply）：填写资料 → 上传证件/实人认证视频 → 提交审核 → 查询进度与初始密码。
维护注意：
1. 本页免登录，凭创建申请返回的一次性查询凭证（accessToken）操作同一申请；凭证保存在 localStorage 的 onboardingDraft，不在 URL、日志或控制台中输出。
2. 主体类型缺省 INDIVIDUAL（个人身份证开店）；个体工商户/企业必须上传营业执照并填写经营者/法定代表人。
3. 个人店实人认证阶段一为人工审核：展示后端下发的随机数字口令，要求本人用前置摄像头录制朗读视频（MP4/WEBM）后上传，不得提示“可代认证”。
4. 审核通过后的初始密码只在结果页展示一次，不得写入 localStorage 或复制到日志；页面刷新后后端不再返回。
5. 上传请求必须带 X-Onboarding-Token；证件只上传到平台私有目录，页面不提供公开文件地址。
6. O3 协议：提交审核前必须阅读并勾选同意全部当前生效协议（GET /onboarding/protocols），逐项 POST /consents 由服务端校验版本与哈希；
   协议版本更新（409）时自动刷新协议列表并清空勾选，用户须重新阅读后再次同意；平台未发布协议时不阻断提交。
7. O9：身份证/营业执照有效期为选填（长期有效留空），随材料一并提交；审核通过后快照到店铺，用于到期提醒与限制上新。
-->
<template>
  <div class="apply-page">
    <header class="apply-header">
      <div class="header-inner">
        <div class="brand">B2B2C<span>开店申请</span></div>
        <div class="header-links">
          <router-link to="/login">商家登录</router-link>
        </div>
      </div>
    </header>

    <main class="apply-main">
      <div class="intro" v-if="!draft.token">
        <h1>三步完成开店申请</h1>
        <p>准备资质 → 在线提交 → 平台人工审核（1-3 个工作日）。个人身份证、个体工商户、企业均可申请，审核通过后系统自动开通店铺与店长账号。</p>
      </div>

      <el-steps :active="step" align-center finish-status="success" class="steps">
        <el-step title="填写资料" />
        <el-step title="上传证件" />
        <el-step title="提交审核" />
        <el-step title="审核进度" />
      </el-steps>

      <!-- 步骤 1：资料 -->
      <section v-show="step === 0" class="panel">
        <div class="panel-head">
          <h2>1. 填写开店资料</h2>
          <p>主体类型默认个人身份证开店；个体工商户/企业需额外填写执照信息。</p>
        </div>
        <el-form :model="form" label-width="130px" label-position="left">
          <el-form-item label="主体类型" required>
            <el-radio-group v-model="form.subjectType" :disabled="locked">
              <el-radio-button label="INDIVIDUAL">个人身份证</el-radio-button>
              <el-radio-button label="INDIVIDUAL_BUSINESS">个体工商户</el-radio-button>
              <el-radio-button label="ENTERPRISE">企业</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item :label="subjectNameLabel" required>
            <el-input v-model="form.subjectName" :placeholder="subjectNamePlaceholder" maxlength="128" />
          </el-form-item>
          <el-form-item label="店铺名称" required>
            <el-input v-model="form.storeName" placeholder="不得包含“旗舰/官方/专卖/专营”等词" maxlength="64" />
          </el-form-item>
          <el-form-item label="身份证号" required>
            <el-input v-model="form.idCardNumber" placeholder="本人有效期内的身份证号码" maxlength="18" />
          </el-form-item>
          <el-form-item label="身份证有效期">
            <el-date-picker v-model="form.idCardExpiry" type="date" value-format="YYYY-MM-DD"
              placeholder="选填；长期有效可留空" :disabled="locked" style="width: 100%" />
          </el-form-item>
          <template v-if="isBusiness">
            <el-form-item label="营业执照号" required>
              <el-input v-model="form.licenseNo" placeholder="统一社会信用代码/注册号" maxlength="64" />
            </el-form-item>
            <el-form-item label="营业执照有效期">
              <el-date-picker v-model="form.licenseExpiry" type="date" value-format="YYYY-MM-DD"
                placeholder="选填；长期有效可留空" :disabled="locked" style="width: 100%" />
            </el-form-item>
            <el-form-item label="经营者/法人" required>
              <el-input v-model="form.legalPerson" placeholder="执照登记的经营者或法定代表人姓名" maxlength="64" />
            </el-form-item>
          </template>
          <el-form-item label="联系电话" required>
            <el-input v-model="form.contactPhone" placeholder="审核结果通知与后续登录账号" maxlength="20" />
          </el-form-item>
          <el-form-item label="联系邮箱">
            <el-input v-model="form.contactEmail" placeholder="选填" maxlength="128" />
          </el-form-item>
        </el-form>
        <div class="panel-actions">
          <el-button v-if="draft.token" @click="loadAttachments">刷新已上传</el-button>
          <el-button type="primary" :loading="saving" @click="saveForm">保存并继续</el-button>
        </div>
      </section>

      <!-- 步骤 2：证件与实人认证 -->
      <section v-show="step === 1" class="panel">
        <div class="panel-head">
          <h2>2. 上传证件{{ isBusiness ? '与营业执照' : '与实人认证视频' }}</h2>
          <p>证件照片支持 JPG/PNG/WEBP，单张不超过 5MB；请保证边缘完整、文字清晰、无反光遮挡。</p>
        </div>

        <div class="upload-grid">
          <upload-card title="身份证人像面" kind="ID_CARD_FRONT" :uploaded="hasKind('ID_CARD_FRONT')"
            accept="image/jpeg,image/png,image/webp" hint="本人身份证正面，信息清晰"
            @upload="uploadFile" />
          <upload-card title="身份证国徽面" kind="ID_CARD_BACK" :uploaded="hasKind('ID_CARD_BACK')"
            accept="image/jpeg,image/png,image/webp" hint="本人身份证反面，有效期清晰"
            @upload="uploadFile" />
          <upload-card v-if="isBusiness" title="营业执照" kind="LICENSE" :uploaded="hasKind('LICENSE')"
            accept="image/jpeg,image/png,image/webp,application/pdf" hint="执照在有效期内，经营范围覆盖主营商品"
            @upload="uploadFile" />
        </div>

        <div v-if="!isBusiness" class="face-verify">
          <div class="face-head">
            <h3>本人实人认证（关键环节）</h3>
            <el-tag type="warning" effect="plain">人工审核</el-tag>
          </div>
          <p>请用手机前置摄像头录制一段视频，本人出镜并匀速朗读下面的随机数字；光线充足、不要戴墨镜或遮挡面部，视频中不得由他人代替。</p>
          <div class="verify-code" v-if="statusView.faceVerifyCode">
            <span>请朗读：</span><strong>{{ statusView.faceVerifyCode }}</strong>
          </div>
          <p class="tip">阶段一未接入第三方人脸核验，平台审核人员会人工比对身份证照片与视频；若信息不一致将驳回。</p>
          <upload-card title="实人认证视频" kind="FACE_VIDEO" :uploaded="hasKind('FACE_VIDEO')"
            accept="video/mp4,video/webm" hint="MP4/WEBM，不超过 20MB，建议 5-10 秒"
            @upload="uploadFile" />
        </div>

        <div class="panel-actions">
          <el-button @click="step = 0" :disabled="locked">上一步</el-button>
          <el-button type="primary" @click="goSubmitStep" :disabled="!draft.token">下一步</el-button>
        </div>
      </section>

      <!-- 步骤 3：提交 -->
      <section v-show="step === 2" class="panel">
        <div class="panel-head">
          <h2>3. 提交审核</h2>
          <p>提交后材料将进入人工审核，审核期间不能修改；如需补件，平台会退回并说明原因。</p>
        </div>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="主体类型">{{ subjectTypeText }}</el-descriptions-item>
          <el-descriptions-item label="主体名称">{{ form.subjectName }}</el-descriptions-item>
          <el-descriptions-item label="店铺名称">{{ form.storeName }}</el-descriptions-item>
          <el-descriptions-item label="联系电话">{{ form.contactPhone }}</el-descriptions-item>
          <el-descriptions-item label="已上传">{{ uploadedKindsText }}</el-descriptions-item>
          <el-descriptions-item label="查询凭证">{{ maskToken(draft.token) }}</el-descriptions-item>
        </el-descriptions>
        <div v-if="protocols.length" class="protocol-box">
          <div class="protocol-title">开店协议签署</div>
          <p class="protocol-tip">请逐一点击阅读并勾选同意以下协议；协议内容如有更新，需重新阅读后再次同意。签署记录（版本、哈希、IP、时间）由平台留存作为证据。</p>
          <div v-for="item in protocols" :key="item.protocolType" class="protocol-item">
            <el-checkbox v-model="consentChecks[item.protocolType]">
              我已阅读并同意
              <a v-if="item.contentUrl" :href="item.contentUrl" target="_blank" rel="noopener" class="protocol-link">《{{ item.title }}》（{{ item.version }}）</a>
              <span v-else>《{{ item.title }}》（{{ item.version }}）</span>
            </el-checkbox>
          </div>
        </div>
        <el-alert class="submit-alert" type="info" :closable="false"
          title="请妥善保存查询凭证：查询进度需要“凭证 + 手机号”，凭证只在创建申请时显示一次。" />
        <div class="panel-actions">
          <el-button @click="step = 1">上一步</el-button>
          <el-button type="primary" :loading="submitting" :disabled="protocols.length > 0 && !allConsented"
            @click="submitApplication">提交审核</el-button>
        </div>
      </section>

      <!-- 步骤 4：进度 -->
      <section v-show="step === 3" class="panel">
        <div class="panel-head">
          <h2>4. 审核进度</h2>
          <p>首次进入会自动查询；后续可凭本机保存的凭证与手机号刷新。</p>
        </div>
        <div class="status-line">
          <el-tag :type="statusTagType" effect="dark" size="large">{{ statusText }}</el-tag>
          <span class="status-sub" v-if="statusView.submittedAt">提交时间：{{ formatDate(statusView.submittedAt) }}</span>
        </div>

        <el-alert v-if="statusView.status === 'SUPPLEMENT_REQUIRED'" type="warning" :closable="false"
          :title="`需要补件：${statusView.rejectReason || '请按审核意见补充材料'}`" />
        <el-alert v-else-if="statusView.status === 'REJECTED'" type="error" :closable="false"
          :title="`申请未通过：${statusView.rejectReason || '材料不符合要求'}`" />

        <div v-if="statusView.initialPassword" class="password-box">
          <div class="password-title">店铺已开通，初始登录密码（仅展示一次）</div>
          <div class="password-value">{{ statusView.initialPassword }}</div>
          <div class="password-actions">
            <el-button type="primary" size="small" @click="copyPassword">复制密码</el-button>
            <router-link to="/login"><el-button size="small">前往商家登录</el-button></router-link>
          </div>
          <div class="password-tip">登录账号为申请手机号 {{ form.contactPhone }}；请首次登录后立即修改密码。</div>
        </div>

        <el-descriptions v-if="statusView.status === 'APPROVED' || statusView.status === 'OPENED'" :column="2" border class="result-desc">
          <el-descriptions-item label="店铺名称">{{ statusView.storeName }}</el-descriptions-item>
          <el-descriptions-item label="登录账号">{{ form.contactPhone }}</el-descriptions-item>
          <el-descriptions-item label="审核时间">{{ formatDate(statusView.reviewedAt) }}</el-descriptions-item>
          <el-descriptions-item label="初始密码">{{ statusView.initialPassword ? '已在上方展示' : '已展示过（不可再次查看）' }}</el-descriptions-item>
        </el-descriptions>

        <div class="panel-actions">
          <el-button @click="refreshStatus">刷新进度</el-button>
          <el-button v-if="canResubmit" type="primary" @click="resumeEdit">修改材料并重新提交</el-button>
          <el-button v-if="statusView.status === 'REJECTED'" type="primary" @click="startNew">重新提交新申请</el-button>
          <el-button v-if="statusView.status === 'APPROVED'" @click="startNew">再开一家店</el-button>
        </div>
      </section>

      <div class="reset-line" v-if="draft.token">
        <el-button link type="danger" @click="forgetDraft">清除本机申请凭证</el-button>
      </div>
    </main>
  </div>
</template>

<script setup>
import { computed, defineComponent, h, onMounted, reactive, ref } from 'vue'
import { ElButton, ElMessage, ElUpload } from 'element-plus'
import request from '../../api/request'

const DRAFT_KEY = 'onboardingDraft'
const step = ref(0)
const saving = ref(false)
const submitting = ref(false)
const locked = ref(false)
const attachments = ref([])
const statusView = ref({})
const protocols = ref([])
const consentChecks = reactive({})

const form = reactive({
  subjectType: 'INDIVIDUAL',
  subjectName: '',
  storeName: '',
  idCardNumber: '',
  idCardExpiry: '',
  licenseNo: '',
  licenseExpiry: '',
  legalPerson: '',
  contactPhone: '',
  contactEmail: ''
})

const draft = reactive({
  id: null,
  token: '',
  phone: ''
})

const isBusiness = computed(() => form.subjectType !== 'INDIVIDUAL')
const subjectNameLabel = computed(() => (form.subjectType === 'ENTERPRISE' ? '企业名称' : '经营者姓名'))
const subjectNamePlaceholder = computed(() => (form.subjectType === 'ENTERPRISE' ? '营业执照登记的企业全称' : '身份证持有人姓名'))

const subjectTypeText = computed(() => ({
  INDIVIDUAL: '个人身份证',
  INDIVIDUAL_BUSINESS: '个体工商户',
  ENTERPRISE: '企业'
}[form.subjectType] || form.subjectType))

const statusText = computed(() => ({
  DRAFT: '草稿',
  SUBMITTED: '已提交，等待审核',
  REVIEWING: '人工审核中',
  APPROVED: '审核通过，店铺已开通',
  OPENED: '店铺已开通',
  REJECTED: '已驳回',
  SUPPLEMENT_REQUIRED: '需要补件',
  WITHDRAWN: '已撤回'
}[statusView.value.status] || '待查询'))

const statusTagType = computed(() => ({
  APPROVED: 'success',
  OPENED: 'success',
  REJECTED: 'danger',
  SUPPLEMENT_REQUIRED: 'warning',
  REVIEWING: 'warning'
}[statusView.value.status] || 'info'))

// 驳回为终态，只能重新发起申请；只有草稿/补件可修改材料并重新提交（与后端状态机一致）。
const canResubmit = computed(() => ['SUPPLEMENT_REQUIRED', 'DRAFT'].includes(statusView.value.status))

// 全部当前生效协议均已勾选才能提交；未发布协议时后端视为签署齐全，不阻断。
const allConsented = computed(() => protocols.value.every(item => !!consentChecks[item.protocolType]))

const uploadedKindsText = computed(() => {
  const labels = {
    ID_CARD_FRONT: '身份证人像面',
    ID_CARD_BACK: '身份证国徽面',
    LICENSE: '营业执照',
    FACE_VIDEO: '实人认证视频',
    OTHER: '其他材料'
  }
  return attachments.value.map(item => labels[item.kind] || item.kind).join('、') || '尚未上传'
})

/** 上传卡片：用函数式组件避免在 SFC 内再声明组件造成编译复杂度。 */
const UploadCard = defineComponent({
  props: { title: String, kind: String, uploaded: Boolean, accept: String, hint: String },
  emits: ['upload'],
  setup(_, { emit }) {
    return () => h('div', { class: ['upload-card', { done: _.uploaded }] }, [
      h('div', { class: 'upload-title' }, [
        h('span', null, _.title),
        h('span', { class: _.uploaded ? 'badge done' : 'badge' }, _.uploaded ? '已上传' : '待上传')
      ]),
      h('div', { class: 'upload-hint' }, _.hint),
      h(ElUpload, {
        showFileList: false,
        accept: _.accept,
        httpRequest: options => emit('upload', options, _.kind)
      }, {
        default: () => h(ElButton, { size: 'small', type: _.uploaded ? 'success' : 'primary', plain: true },
          () => (_.uploaded ? '重新上传' : '选择文件'))
      })
    ])
  }
})

function loadDraft() {
  try {
    const raw = localStorage.getItem(DRAFT_KEY)
    if (!raw) return
    const saved = JSON.parse(raw)
    draft.id = saved.id || null
    draft.token = saved.token || ''
    draft.phone = saved.phone || ''
    Object.assign(form, saved.form || {})
  } catch {
    localStorage.removeItem(DRAFT_KEY)
  }
}

function persistDraft() {
  localStorage.setItem(DRAFT_KEY, JSON.stringify({
    id: draft.id,
    token: draft.token,
    phone: draft.phone,
    form: { ...form }
  }))
}

function maskToken(token) {
  if (!token) return ''
  return token.length <= 12 ? token : `${token.slice(0, 6)}…${token.slice(-4)}`
}

function validateForm() {
  if (!form.subjectName.trim()) return '请填写主体名称'
  if (!form.storeName.trim()) return '请填写店铺名称'
  if (!/^[0-9Xx]{15,18}$/.test(form.idCardNumber.trim())) return '请填写有效的身份证号'
  if (!form.contactPhone.trim() || form.contactPhone.trim().length < 6) return '请填写有效联系电话'
  if (isBusiness.value && !form.licenseNo.trim()) return '请填写营业执照号'
  if (isBusiness.value && !form.legalPerson.trim()) return '请填写经营者/法定代表人'
  return ''
}

async function saveForm() {
  const error = validateForm()
  if (error) {
    ElMessage.warning(error)
    return
  }
  saving.value = true
  try {
    const payload = { ...form, contactPhone: form.contactPhone.trim(), subjectName: form.subjectName.trim(), storeName: form.storeName.trim() }
    let response
    if (draft.token) {
      response = await request.put(`/onboarding/applications/${draft.id}`, payload, {
        headers: { 'X-Onboarding-Token': draft.token }
      })
    } else {
      response = await request.post('/onboarding/applications', payload)
      draft.id = response.data.id
      draft.token = response.data.accessToken
      draft.phone = payload.contactPhone
      persistDraft()
      ElMessage.success('申请已创建，查询凭证已保存在本机')
    }
    statusView.value = response.data
    await loadAttachments()
    step.value = 1
  } finally {
    saving.value = false
  }
}

async function loadAttachments() {
  if (!draft.id || !draft.token) return
  try {
    const res = await request.get(`/onboarding/applications/${draft.id}/attachments`, {
      headers: { 'X-Onboarding-Token': draft.token }
    })
    attachments.value = res.data || []
  } catch {
    attachments.value = []
  }
}

function hasKind(kind) {
  return attachments.value.some(item => item.kind === kind)
}

async function uploadFile(options, kind) {
  const { file } = options
  const video = kind === 'FACE_VIDEO'
  const limit = video ? 20 : 5
  if (file.size > limit * 1024 * 1024) {
    ElMessage.warning(`文件不得超过 ${limit}MB`)
    options.onError?.(new Error('文件过大'))
    return
  }
  const body = new FormData()
  body.append('file', file)
  try {
    // 不手写 multipart Content-Type：浏览器需要自动补 boundary，手写会导致后端解析不到文件。
    await request.post(`/onboarding/applications/${draft.id}/attachments?kind=${kind}`, body, {
      headers: { 'X-Onboarding-Token': draft.token }
    })
    ElMessage.success('上传成功')
    await loadAttachments()
    options.onSuccess?.({})
  } catch (error) {
    options.onError?.(error)
  }
}

async function loadProtocols() {
  try {
    const res = await request.get('/onboarding/protocols')
    protocols.value = res.data || []
  } catch {
    protocols.value = []
  }
}

async function goSubmitStep() {
  await loadProtocols()
  step.value = 2
}

/** 逐项签署当前生效协议；任一失败（如 409 版本更新）刷新协议并清空勾选，强制重新阅读。 */
async function submitConsents() {
  try {
    for (const item of protocols.value) {
      await request.post(`/onboarding/applications/${draft.id}/consents`, {
        protocolType: item.protocolType,
        version: item.version,
        contentHash: item.contentHash
      }, { headers: { 'X-Onboarding-Token': draft.token } })
    }
  } catch (error) {
    await loadProtocols()
    protocols.value.forEach(item => { consentChecks[item.protocolType] = false })
    throw error
  }
}

async function submitApplication() {
  const required = ['ID_CARD_FRONT', 'ID_CARD_BACK']
  if (isBusiness.value) required.push('LICENSE')
  else required.push('FACE_VIDEO')
  if (!required.every(hasKind)) {
    ElMessage.warning('请先上传全部必传证件' + (isBusiness.value ? '（身份证正反面与营业执照）' : '（身份证正反面与实人认证视频）'))
    step.value = 1
    return
  }
  if (protocols.value.length && !allConsented.value) {
    ElMessage.warning('请先阅读并勾选同意全部开店协议')
    return
  }
  submitting.value = true
  try {
    if (protocols.value.length) {
      await submitConsents()
    }
    const res = await request.post(`/onboarding/applications/${draft.id}/submit`, null, {
      headers: { 'X-Onboarding-Token': draft.token }
    })
    statusView.value = res.data
    locked.value = true
    step.value = 3
    ElMessage.success('已提交，平台将在 1-3 个工作日内完成审核')
    await refreshStatus()
  } finally {
    submitting.value = false
  }
}

async function refreshStatus() {
  if (!draft.id || !draft.token) return
  try {
    const res = await request.get(`/onboarding/applications/${draft.id}`, {
      params: { phone: form.contactPhone.trim() || draft.phone },
      headers: { 'X-Onboarding-Token': draft.token },
      skipErrorToast: true
    })
    statusView.value = res.data
    if (['DRAFT', 'SUPPLEMENT_REQUIRED'].includes(res.data.status)) {
      locked.value = false
    }
    if (res.data.status === 'APPROVED' || res.data.status === 'OPENED') {
      locked.value = true
    }
  } catch {
    ElMessage.warning('进度查询失败，请确认查询凭证与手机号一致')
  }
}

function resumeEdit() {
  locked.value = false
  step.value = 1
}

function copyPassword() {
  const value = statusView.value.initialPassword
  if (!value) return
  if (navigator.clipboard?.writeText) {
    navigator.clipboard.writeText(value).then(() => ElMessage.success('已复制初始密码')).catch(() => ElMessage.warning('复制失败，请手动复制'))
  } else {
    ElMessage.warning('当前浏览器不支持自动复制，请手动复制')
  }
}

function startNew() {
  localStorage.removeItem(DRAFT_KEY)
  draft.id = null
  draft.token = ''
  draft.phone = ''
  attachments.value = []
  statusView.value = {}
  locked.value = false
  step.value = 0
  Object.assign(form, { subjectType: 'INDIVIDUAL', subjectName: '', storeName: '', idCardNumber: '', licenseNo: '', legalPerson: '', contactPhone: '', contactEmail: '' })
}

function forgetDraft() {
  localStorage.removeItem(DRAFT_KEY)
  draft.id = null
  draft.token = ''
  draft.phone = ''
  attachments.value = []
  statusView.value = {}
  step.value = 0
  ElMessage.info('已清除本机查询凭证，原有申请仍保留在平台侧')
}

function formatDate(value) {
  if (!value) return ''
  const text = String(value).replace('T', ' ')
  return text.length > 16 ? text.slice(0, 16) : text
}

onMounted(async () => {
  loadDraft()
  if (!draft.token) return
  await refreshStatus()
  if (['SUBMITTED', 'REVIEWING', 'APPROVED', 'OPENED', 'WITHDRAWN', 'REJECTED'].includes(statusView.value.status)) {
    locked.value = true
    step.value = 3
  } else {
    await loadAttachments()
    step.value = statusView.value.status === 'SUPPLEMENT_REQUIRED' ? 1 : 0
  }
})
</script>

<style scoped>
.apply-page {
  min-height: 100vh;
  background: #f5f6f8;
  color: #1f2937;
}

.apply-header {
  background: #fff;
  border-bottom: 1px solid #e5e7eb;
}

.header-inner {
  max-width: 1000px;
  margin: 0 auto;
  height: 64px;
  padding: 0 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.brand {
  font-size: 20px;
  font-weight: 800;
  color: #ff5000;
}

.brand span {
  margin-left: 8px;
  font-size: 14px;
  font-weight: 500;
  color: #4b5563;
}

.header-links a {
  color: #ff5000;
  text-decoration: none;
  font-size: 14px;
}

.apply-main {
  max-width: 1000px;
  margin: 0 auto;
  padding: 32px 20px 64px;
}

.intro {
  background: linear-gradient(135deg, #fff3ec, #ffe8db);
  border: 1px solid #ffd2ba;
  border-radius: 12px;
  padding: 24px 28px;
  margin-bottom: 28px;
}

.intro h1 {
  margin: 0 0 10px;
  font-size: 22px;
}

.intro p {
  margin: 0;
  font-size: 14px;
  line-height: 1.8;
  color: #7c2d12;
}

.steps {
  margin-bottom: 24px;
}

.panel {
  background: #fff;
  border-radius: 12px;
  padding: 28px 32px;
  box-shadow: 0 10px 30px rgba(15, 23, 42, 0.06);
}

.panel-head h2 {
  margin: 0 0 6px;
  font-size: 18px;
}

.panel-head p {
  margin: 0 0 22px;
  font-size: 13px;
  color: #6b7280;
}

.panel-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 24px;
}

.upload-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 16px;
}

:deep(.upload-card) {
  border: 1px dashed #d1d5db;
  border-radius: 10px;
  padding: 16px;
  background: #fbfcfd;
}

:deep(.upload-card.done) {
  border-color: #67c23a;
  background: #f6fbf3;
}

:deep(.upload-title) {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 8px;
}

:deep(.badge) {
  font-size: 11px;
  color: #9ca3af;
  border: 1px solid #e5e7eb;
  border-radius: 999px;
  padding: 1px 8px;
}

:deep(.badge.done) {
  color: #3f8f24;
  border-color: #b7dfa1;
}

:deep(.upload-hint) {
  font-size: 12px;
  color: #6b7280;
  margin-bottom: 12px;
  min-height: 32px;
}

.face-verify {
  margin-top: 26px;
  padding: 20px;
  border: 1px solid #fde68a;
  background: #fffbeb;
  border-radius: 10px;
}

.face-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.face-head h3 {
  margin: 0;
  font-size: 15px;
}

.face-verify p {
  font-size: 13px;
  color: #78350f;
  line-height: 1.8;
  margin: 0 0 12px;
}

.verify-code {
  font-size: 14px;
  margin-bottom: 12px;
}

.verify-code strong {
  font-size: 26px;
  letter-spacing: 6px;
  color: #b45309;
  margin-left: 8px;
}

.tip {
  color: #92400e !important;
  font-size: 12px !important;
}

.protocol-box {
  margin-top: 18px;
  padding: 16px 18px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #fafbfc;
}

.protocol-title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 6px;
}

.protocol-tip {
  font-size: 12px;
  color: #6b7280;
  line-height: 1.7;
  margin: 0 0 10px;
}

.protocol-item {
  padding: 4px 0;
}

.protocol-link {
  color: #ff5000;
  text-decoration: none;
}

.protocol-link:hover {
  text-decoration: underline;
}

.submit-alert {
  margin-top: 18px;
}

.status-line {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 20px;
}

.status-sub {
  font-size: 13px;
  color: #6b7280;
}

.password-box {
  margin-top: 20px;
  padding: 20px;
  border: 1px solid #b7dfa1;
  background: #f6fbf3;
  border-radius: 10px;
}

.password-title {
  font-size: 14px;
  color: #3f6212;
  font-weight: 600;
}

.password-value {
  margin: 12px 0;
  font-size: 26px;
  letter-spacing: 3px;
  font-weight: 800;
  color: #1f2937;
  font-family: Consolas, monospace;
}

.password-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}

.password-tip {
  margin-top: 12px;
  font-size: 12px;
  color: #6b7280;
}

.result-desc {
  margin-top: 20px;
}

.reset-line {
  text-align: center;
  margin-top: 20px;
}
</style>
