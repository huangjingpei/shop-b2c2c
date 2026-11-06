<!--
文件：Onboarding.vue
用途：平台超管的开店申请审核页：查看材料/证件预览、开始审核、通过自动建店（O4 建店为待开通）、驳回与要求补件。
维护注意：
1. 证件与实人认证视频只通过带鉴权的下载接口读取，Blob URL 用完立即 revoke；不得把私有目录路径拼到页面或日志。
2. 审核通过后系统自动生成店铺编码、登录账号（申请手机号）与初始密码；initialPassword 只在本页弹窗展示一次，不写入 localStorage、表格或通知消息。
   O4：通过仅代表资质合格，店铺为“待开通”，商家签齐开店协议后自动开通；双人复核开启时首次通过只推进到待复审，无初始密码。
3. 通过/驳回/补件都必须提交人工审核原因，前端不可把本地角色当作授权；所有动作最终由后端超管接口校验。
4. 状态机动作只发 START_REVIEW/APPROVE/REJECT/SUPPLEMENT_REQUIRED，不直接修改申请状态。
5. O7 审核工作台：费率只支持 0（推广期，必填推广原因）或 100–1000bps；驳回/补件必选原因码（OTHER 必填说明）；
   复核人下拉来自 /admin/settlement/reviewers（已排除当前账号），严格模式必填；列表支持状态与 SLA(48h) 超时筛选。
   规则口径与 shared/onboardingReview.test.mjs、utils/financeReview.mjs 一致，修改须同步。
-->
<template>
  <div class="onboarding-page">
    <div class="page-head">
      <div>
        <h2>开店申请审核</h2>
        <p>个人身份证、个体工商户、企业统一在此人工审核；通过后生成待开通店铺与店长账号，商家签齐协议后自动营业。</p>
      </div>
      <el-button :loading="loading" @click="loadApplications">刷新</el-button>
    </div>

    <el-alert type="info" :closable="false" class="notice"
      title="审核要点：身份证/执照照片清晰且在有效期；个人店需核对实人认证视频中的本人与朗读数字；店铺名称不得含旗舰/官方/专卖/专营等词。" />

    <div class="filter-bar">
      <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width: 150px">
        <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-checkbox v-model="filterSlaOnly">仅看超时（提交超 {{ REVIEW_SLA_HOURS }}h 未审结）</el-checkbox>
    </div>

    <el-table :data="filteredApplications" v-loading="loading" border stripe>
      <el-table-column prop="id" label="申请ID" width="80" />
      <el-table-column label="主体类型" width="110">
        <template #default="{ row }">{{ subjectText(row.subjectType) }}</template>
      </el-table-column>
      <el-table-column prop="subjectName" label="主体名称" min-width="140" show-overflow-tooltip />
      <el-table-column prop="storeName" label="店铺名称" min-width="130" show-overflow-tooltip />
      <el-table-column prop="contactPhone" label="联系电话" width="130" />
      <el-table-column label="状态" width="150">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" effect="plain">{{ statusText(row.status) }}</el-tag>
          <el-tag v-if="isSlaBreach(row)" type="danger" effect="plain" class="sla-tag">超时</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="复核阶段" width="100">
        <template #default="{ row }">{{ reviewStageText(row.reviewStage) }}</template>
      </el-table-column>
      <el-table-column label="提交时间" width="160">
        <template #default="{ row }">{{ formatDate(row.submittedAt) || '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">查看</el-button>
          <el-button v-if="canReview(row.status)" link type="primary" @click="openReview(row)">审核</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 详情 -->
    <el-dialog v-model="detailVisible" title="申请详情" width="760px">
      <el-descriptions :column="2" border v-if="detail">
        <el-descriptions-item label="申请ID">{{ detail.id }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ statusText(detail.status) }}</el-descriptions-item>
        <el-descriptions-item label="主体类型">{{ subjectText(detail.subjectType) }}</el-descriptions-item>
        <el-descriptions-item label="主体名称">{{ detail.subjectName }}</el-descriptions-item>
        <el-descriptions-item label="店铺名称">{{ detail.storeName }}</el-descriptions-item>
        <el-descriptions-item label="联系电话">{{ detail.contactPhone }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.subjectType === 'INDIVIDUAL'" label="实人认证口令">
          {{ detail.faceVerifyCode || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="联系邮箱">{{ detail.contactEmail || '-' }}</el-descriptions-item>
        <el-descriptions-item label="身份证">{{ detail.idCardMasked || '-' }}</el-descriptions-item>
        <el-descriptions-item label="营业执照号">{{ detail.licenseNoMasked || '-' }}</el-descriptions-item>
        <el-descriptions-item label="经营者/法人">{{ detail.legalPerson || '-' }}</el-descriptions-item>
        <el-descriptions-item label="提交时间">{{ formatDate(detail.submittedAt) || '-' }}</el-descriptions-item>
        <el-descriptions-item label="审核时间">{{ formatDate(detail.reviewedAt) || '-' }}</el-descriptions-item>
        <el-descriptions-item label="复核阶段">{{ reviewStageText(detail.reviewStage) }}</el-descriptions-item>
        <el-descriptions-item label="原因码">{{ detail.rejectCode || '-' }}</el-descriptions-item>
        <el-descriptions-item label="审核意见" :span="2">{{ detail.rejectReason || '-' }}</el-descriptions-item>
      </el-descriptions>

      <div class="attach-block">
        <h4>证件与认证视频</h4>
        <div v-if="attachments.length" class="attach-list">
          <div v-for="item in attachments" :key="item.id" class="attach-item">
            <div class="attach-meta">
              <strong>{{ kindText(item.kind) }}</strong>
              <span>{{ formatSize(item.sizeBytes) }} · {{ formatDate(item.createdAt) }}</span>
            </div>
            <el-button size="small" type="primary" plain @click="previewAttachment(item)">预览</el-button>
          </div>
        </div>
        <el-empty v-else description="暂无已上传证件" :image-size="70" />
      </div>

      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button v-if="canReview(detail?.status)" type="primary" @click="openReview(detail)">进入审核</el-button>
      </template>
    </el-dialog>

    <!-- 审核 -->
    <el-dialog v-model="reviewVisible" title="审核开店申请" width="560px">
      <el-form label-width="110px" v-if="current">
        <el-form-item label="申请主体">{{ current.subjectName }}</el-form-item>
        <el-form-item label="店铺名称">
          <el-input v-model="reviewForm.storeName" maxlength="64" placeholder="默认使用申请填写的店铺名称" />
        </el-form-item>
        <el-form-item label="初始费率">
          <el-input-number v-model="reviewForm.rateBps" :min="0" :max="1000" :step="50" />
          <span class="rate-tip">万分比；0（推广期）或 100–1000（1%–10%）；留空使用平台默认费率（3.00%）</span>
        </el-form-item>
        <el-form-item v-if="reviewForm.rateBps === 0" label="推广原因" required>
          <el-input v-model="reviewForm.promotionReason" maxlength="128"
            placeholder="0% 仅限推广期，必须填写推广原因（随费率政策留痕）" />
        </el-form-item>
        <el-form-item label="费率复核人" :required="reviewerPolicy.dualApprovalRequired">
          <el-select v-model="reviewForm.rateConfirmedBy" clearable style="width: 100%"
            :placeholder="reviewerPlaceholder">
            <el-option v-for="item in reviewerPolicy.candidates" :key="item.id"
              :label="reviewerLabel(item)" :value="item.id" />
          </el-select>
          <div class="rate-tip">开店初始费率双人复核；候选已排除当前登录账号，严格模式必填。</div>
        </el-form-item>
        <el-alert v-if="reviewerPolicy.notice" :title="reviewerPolicy.notice" type="warning"
          :closable="false" class="review-notice" />
        <el-form-item label="原因码">
          <el-select v-model="reviewForm.rejectCode" clearable style="width: 100%"
            placeholder="驳回/要求补件时必选">
            <el-option v-for="item in REJECT_CODES" :key="item.code" :label="`${item.label}（${item.code}）`"
              :value="item.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="审核意见">
          <el-input v-model="reviewForm.reason" type="textarea" :rows="3" maxlength="512"
            placeholder="通过时可填写复核说明；驳回/补件必须选择原因码，其他原因码必须填写说明" />
        </el-form-item>
      </el-form>
      <el-alert type="warning" :closable="false"
        title="通过后系统自动生成店铺编码、登录账号（申请手机号）与初始密码；店铺先为“待开通”，商家签齐开店协议后自动营业。初始密码只在结果弹窗展示一次，请当场转交商家。" />
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button type="success" :loading="reviewing" @click="approve">审核通过</el-button>
        <el-button v-if="current && current.status !== 'REVIEWING'" :loading="reviewing" @click="startReview">开始审核</el-button>
        <el-button type="warning" :loading="reviewing" @click="supplement">要求补件</el-button>
        <el-button type="danger" :loading="reviewing" @click="reject">驳回</el-button>
      </template>
    </el-dialog>

    <!-- 初始密码结果 -->
    <el-dialog v-model="resultVisible" title="审核已通过，店铺待开通" width="520px" :close-on-click-modal="false">
      <div class="result-box">
        <p>系统已创建待开通店铺，请将以下信息转交商家（仅本次展示）；商家登录后签齐开店协议即自动营业：</p>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="店铺名称">{{ result.storeName }}</el-descriptions-item>
          <el-descriptions-item label="店铺编码">{{ result.storeCode }}</el-descriptions-item>
          <el-descriptions-item label="登录账号">{{ result.username }}</el-descriptions-item>
          <el-descriptions-item label="初始密码">
            <strong class="initial-password">{{ result.initialPassword }}</strong>
          </el-descriptions-item>
        </el-descriptions>
        <el-alert type="warning" :closable="false" class="result-tip"
          title="初始密码只出现在本次响应；关闭弹窗后无法再次查看，请复制后安全转交。" />
      </div>
      <template #footer>
        <el-button @click="copyPassword" type="primary">复制账号与密码</el-button>
        <el-button @click="resultVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 证件预览 -->
    <el-dialog v-model="previewVisible" :title="previewTitle" width="720px" @closed="releasePreview">
      <img v-if="previewType.startsWith('image/')" :src="previewUrl" class="preview-image" alt="证件预览" />
      <video v-else-if="previewType.startsWith('video/')" :src="previewUrl" controls class="preview-video" />
      <iframe v-else :src="previewUrl" class="preview-pdf" title="证件预览" />
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../../api/request'
import { reviewerState, validateReviewer } from '../../utils/financeReview.mjs'
import {
  REJECT_CODES,
  REVIEW_SLA_HOURS,
  buildReviewPayload,
  isSlaBreach,
  reviewStageText,
  validateRateBps,
  validateRejectPayload
} from '../../utils/onboardingReview.mjs'

const BASE = '/admin/merchant-onboarding/applications'

const loading = ref(false)
const applications = ref([])
const detailVisible = ref(false)
const detail = ref(null)
const attachments = ref([])

const filterStatus = ref('')
const filterSlaOnly = ref(false)

const reviewVisible = ref(false)
const current = ref(null)
const reviewing = ref(false)
const reviewForm = reactive({
  storeName: '', rateBps: undefined, promotionReason: '', rateConfirmedBy: '', rejectCode: '', reason: ''
})

// O7：复核人候选由后端按 SUPERADMIN + 启用 + 排除当前账号返回，页面不做角色猜测。
const reviewers = ref([])
const dualApprovalRequired = ref(false)
const currentUserId = ref(null)
const reviewerPolicy = computed(() => reviewerState({
  dualApprovalRequired: dualApprovalRequired.value,
  reviewers: reviewers.value,
  currentUserId: currentUserId.value
}))
const reviewerPlaceholder = computed(() => reviewerPolicy.value.dualApprovalRequired
  ? '必须选择复核人'
  : '不选则按当前操作人记录同人复核（仅开发/试点）')
const reviewerLabel = item => `${item.realName || item.username || '管理员'}（${item.username}）`

const resultVisible = ref(false)
const result = reactive({ storeName: '', storeCode: '', username: '', initialPassword: '' })

const previewVisible = ref(false)
const previewUrl = ref('')
const previewType = ref('')
const previewTitle = ref('')

const subjectText = type => ({
  INDIVIDUAL: '个人身份证',
  INDIVIDUAL_BUSINESS: '个体工商户',
  ENTERPRISE: '企业'
}[type] || type || '-')

const statusText = status => ({
  DRAFT: '草稿',
  SUBMITTED: '已提交',
  REVIEWING: '审核中',
  APPROVED: '已通过',
  OPENED: '已开通',
  REJECTED: '已驳回',
  SUPPLEMENT_REQUIRED: '需补件',
  WITHDRAWN: '已撤回'
}[status] || status || '-')

const statusOptions = [
  'DRAFT', 'SUBMITTED', 'REVIEWING', 'APPROVED', 'OPENED', 'REJECTED', 'SUPPLEMENT_REQUIRED', 'WITHDRAWN'
].map(value => ({ value, label: statusText(value) }))

const statusTag = status => ({
  APPROVED: 'success',
  OPENED: 'success',
  REJECTED: 'danger',
  SUPPLEMENT_REQUIRED: 'warning',
  REVIEWING: 'warning'
}[status] || 'info')

const kindText = kind => ({
  ID_CARD_FRONT: '身份证人像面',
  ID_CARD_BACK: '身份证国徽面',
  LICENSE: '营业执照',
  FACE_VIDEO: '实人认证视频',
  OTHER: '其他材料'
}[kind] || kind)

const canReview = status => ['SUBMITTED', 'REVIEWING'].includes(status)

const filteredApplications = computed(() => applications.value.filter(row => {
  if (filterStatus.value && row.status !== filterStatus.value) return false
  if (filterSlaOnly.value && !isSlaBreach(row)) return false
  return true
}))

async function loadApplications() {
  loading.value = true
  try {
    const res = await request.get(BASE)
    applications.value = res.data || []
  } finally {
    loading.value = false
  }
}

async function loadReviewers() {
  try {
    const res = await request.get('/admin/settlement/reviewers', { skipErrorToast: true })
    dualApprovalRequired.value = Boolean(res.data?.dualApprovalRequired)
    reviewers.value = Array.isArray(res.data?.reviewers) ? res.data.reviewers : []
    currentUserId.value = res.data?.currentUserId ?? null
  } catch (error) {
    reviewers.value = []
    ElMessage.warning(error.response?.data?.message || '复核人候选加载失败，提交时仍由后端校验')
  }
}

async function openDetail(row) {
  detail.value = null
  attachments.value = []
  try {
    const [detailRes, attachRes] = await Promise.all([
      request.get(`${BASE}/${row.id}`),
      request.get(`${BASE}/${row.id}/attachments`)
    ])
    detail.value = detailRes.data
    attachments.value = attachRes.data || []
    detailVisible.value = true
  } catch {
    // 请求层已提示
  }
}

function openReview(row) {
  if (!row) return
  current.value = row
  reviewForm.storeName = row.storeName || ''
  reviewForm.rateBps = undefined
  reviewForm.promotionReason = ''
  reviewForm.rateConfirmedBy = ''
  reviewForm.rejectCode = ''
  reviewForm.reason = ''
  detailVisible.value = false
  reviewVisible.value = true
}

async function callReview(action, extra = {}) {
  if (!current.value) return null
  reviewing.value = true
  try {
    const res = await request.post(`${BASE}/${current.value.id}/review`, { action, ...extra })
    return res.data
  } finally {
    reviewing.value = false
  }
}

async function startReview() {
  try {
    await callReview('START_REVIEW', buildReviewPayload({ action: 'START_REVIEW', reason: reviewForm.reason }))
    ElMessage.success('已进入审核中')
    reviewVisible.value = false
    await loadApplications()
  } catch {
    // 请求层已提示
  }
}

async function approve() {
  const rateCheck = validateRateBps(reviewForm.rateBps, reviewForm.promotionReason)
  if (!rateCheck.ok) {
    ElMessage.warning(rateCheck.message)
    return
  }
  const selection = validateReviewer({
    dualApprovalRequired: dualApprovalRequired.value,
    reviewers: reviewers.value,
    currentUserId: currentUserId.value,
    reviewerId: reviewForm.rateConfirmedBy
  })
  if (!selection.ok) {
    ElMessage.warning(selection.message)
    return
  }
  if (selection.code === 'SAME_OPERATOR') {
    // 宽松模式：显式提示同人复核口径（仅开发/试点），不改变后端判定。
    ElMessage.warning(selection.message)
  }
  try {
    const view = await callReview('APPROVE', buildReviewPayload({
      action: 'APPROVE',
      reason: reviewForm.reason,
      storeName: reviewForm.storeName,
      rateBps: reviewForm.rateBps,
      promotionReason: reviewForm.promotionReason,
      rateConfirmedBy: selection.confirmedBy
    }))
    if (!view) return
    if (!view.initialPassword) {
      // 双人复核开启：本次通过只推进到待复审，由另一位超管终审后才建店发号。
      ElMessage.success('初审已通过，待另一位超管复审通过后建店')
      reviewVisible.value = false
      await loadApplications()
      return
    }
    result.storeName = view?.storeName || reviewForm.storeName
    result.storeCode = view?.storeCode || '-'
    result.username = current.value?.contactPhone || ''
    result.initialPassword = view?.initialPassword || ''
    resultVisible.value = true
    reviewVisible.value = false
    await loadApplications()
  } catch {
    // 请求层已提示
  }
}

async function reject() {
  const check = validateRejectPayload({
    action: 'REJECT', rejectCode: reviewForm.rejectCode, reason: reviewForm.reason
  })
  if (!check.ok) {
    ElMessage.warning(check.message)
    return
  }
  try {
    await ElMessageBox.confirm(
      '驳回为终态，申请人只能重新提交新申请；确认驳回该申请？', '驳回确认', { type: 'warning' }
    )
    await callReview('REJECT', buildReviewPayload({
      action: 'REJECT', rejectCode: reviewForm.rejectCode, reason: reviewForm.reason
    }))
    ElMessage.success('已驳回')
    reviewVisible.value = false
    await loadApplications()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      // 请求层已提示
    }
  }
}

async function supplement() {
  const check = validateRejectPayload({
    action: 'SUPPLEMENT_REQUIRED', rejectCode: reviewForm.rejectCode, reason: reviewForm.reason
  })
  if (!check.ok) {
    ElMessage.warning(check.message)
    return
  }
  try {
    await callReview('SUPPLEMENT_REQUIRED', buildReviewPayload({
      action: 'SUPPLEMENT_REQUIRED', rejectCode: reviewForm.rejectCode, reason: reviewForm.reason
    }))
    ElMessage.success('已退回补件')
    reviewVisible.value = false
    await loadApplications()
  } catch {
    // 请求层已提示
  }
}

async function previewAttachment(item) {
  try {
    const blob = await request.get(`${BASE}/${detail.value.id}/attachments/${item.id}`, {
      responseType: 'blob',
      skipErrorToast: true
    })
    releasePreview()
    previewType.value = blob.type || item.contentType || ''
    previewUrl.value = URL.createObjectURL(blob)
    previewTitle.value = `${kindText(item.kind)}预览`
    previewVisible.value = true
  } catch {
    ElMessage.error('证件读取失败，请确认文件仍存在')
  }
}

function releasePreview() {
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = ''
  }
}

function copyPassword() {
  const text = `店铺：${result.storeName}\n登录账号：${result.username}\n初始密码：${result.initialPassword}`
  if (navigator.clipboard?.writeText) {
    navigator.clipboard.writeText(text)
      .then(() => ElMessage.success('已复制'))
      .catch(() => ElMessage.warning('复制失败，请手动记录'))
  } else {
    ElMessage.warning('当前浏览器不支持自动复制，请手动记录')
  }
}

function formatDate(value) {
  if (!value) return ''
  const text = String(value).replace('T', ' ')
  return text.length > 16 ? text.slice(0, 16) : text
}

function formatSize(bytes) {
  if (!bytes) return '0KB'
  const size = Number(bytes)
  if (size < 1024 * 1024) return `${Math.ceil(size / 1024)}KB`
  return `${(size / 1024 / 1024).toFixed(1)}MB`
}

onMounted(() => {
  loadApplications()
  loadReviewers()
})
</script>

<style scoped>
.onboarding-page {
  background: #fff;
  border-radius: 10px;
  padding: 22px 24px;
}

.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
}

.page-head h2 {
  margin: 0 0 6px;
  font-size: 18px;
}

.page-head p {
  margin: 0;
  font-size: 13px;
  color: #6b7280;
}

.notice {
  margin-bottom: 16px;
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 14px;
}

.sla-tag {
  margin-left: 6px;
}

.review-notice {
  margin: 0 0 12px 110px;
}

.attach-block {
  margin-top: 20px;
}

.attach-block h4 {
  margin: 0 0 12px;
  font-size: 14px;
}

.attach-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.attach-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 10px 14px;
  background: #fbfcfd;
}

.attach-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: #6b7280;
}

.attach-meta strong {
  font-size: 13px;
  color: #1f2937;
}

.rate-tip {
  margin-left: 10px;
  font-size: 12px;
  color: #9ca3af;
}

.result-box p {
  margin: 0 0 14px;
  font-size: 13px;
  color: #374151;
}

.initial-password {
  font-size: 18px;
  letter-spacing: 2px;
  color: #b45309;
}

.result-tip {
  margin-top: 14px;
}

.preview-image,
.preview-video,
.preview-pdf {
  width: 100%;
  max-height: 70vh;
  object-fit: contain;
  border-radius: 8px;
  background: #f3f4f6;
}

.preview-pdf {
  height: 70vh;
  border: none;
}
</style>
