<!--
文件：Onboarding.vue
用途：平台超管的开店申请审核页：查看材料/证件预览、开始审核、通过自动开店、驳回与要求补件。
维护注意：
1. 证件与实人认证视频只通过带鉴权的下载接口读取，Blob URL 用完立即 revoke；不得把私有目录路径拼到页面或日志。
2. 审核通过后系统自动生成店铺编码、登录账号（申请手机号）与初始密码；initialPassword 只在本页弹窗展示一次，不写入 localStorage、表格或通知消息。
3. 通过/驳回/补件都必须提交人工审核原因，前端不可把本地角色当作授权；所有动作最终由后端超管接口校验。
4. 状态机动作只发 START_REVIEW/APPROVE/REJECT/SUPPLEMENT_REQUIRED，不直接修改申请状态。
-->
<template>
  <div class="onboarding-page">
    <div class="page-head">
      <div>
        <h2>开店申请审核</h2>
        <p>个人身份证、个体工商户、企业统一在此人工审核；通过后自动开通店铺与店长账号。</p>
      </div>
      <el-button :loading="loading" @click="loadApplications">刷新</el-button>
    </div>

    <el-alert type="info" :closable="false" class="notice"
      title="审核要点：身份证/执照照片清晰且在有效期；个人店需核对实人认证视频中的本人与朗读数字；店铺名称不得含旗舰/官方/专卖/专营等词。" />

    <el-table :data="applications" v-loading="loading" border stripe>
      <el-table-column prop="id" label="申请ID" width="80" />
      <el-table-column label="主体类型" width="110">
        <template #default="{ row }">{{ subjectText(row.subjectType) }}</template>
      </el-table-column>
      <el-table-column prop="subjectName" label="主体名称" min-width="140" show-overflow-tooltip />
      <el-table-column prop="storeName" label="店铺名称" min-width="130" show-overflow-tooltip />
      <el-table-column prop="contactPhone" label="联系电话" width="130" />
      <el-table-column label="状态" width="130">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" effect="plain">{{ statusText(row.status) }}</el-tag>
        </template>
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
      <el-form label-width="100px" v-if="current">
        <el-form-item label="申请主体">{{ current.subjectName }}</el-form-item>
        <el-form-item label="店铺名称">
          <el-input v-model="reviewForm.storeName" maxlength="64" placeholder="默认使用申请填写的店铺名称" />
        </el-form-item>
        <el-form-item label="初始费率">
          <el-input-number v-model="reviewForm.rateBps" :min="0" :max="10000" :step="50" />
          <span class="rate-tip">单位：万分比；留空使用平台默认费率（3.00%）</span>
        </el-form-item>
        <el-form-item label="审核意见">
          <el-input v-model="reviewForm.reason" type="textarea" :rows="3" maxlength="512"
            placeholder="通过时可填写复核说明；驳回/补件必须填写原因" />
        </el-form-item>
      </el-form>
      <el-alert type="warning" :closable="false"
        title="通过后系统自动生成店铺编码、登录账号（申请手机号）与初始密码；初始密码只在结果弹窗展示一次，请当场转交商家。" />
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button type="success" :loading="reviewing" @click="approve">审核通过并开店</el-button>
        <el-button v-if="current && current.status !== 'REVIEWING'" :loading="reviewing" @click="startReview">开始审核</el-button>
        <el-button type="warning" :loading="reviewing" @click="supplement">要求补件</el-button>
        <el-button type="danger" :loading="reviewing" @click="reject">驳回</el-button>
      </template>
    </el-dialog>

    <!-- 初始密码结果 -->
    <el-dialog v-model="resultVisible" title="店铺已开通" width="520px" :close-on-click-modal="false">
      <div class="result-box">
        <p>系统已自动开店，请将以下信息转交商家（仅本次展示）：</p>
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
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../../api/request'

const BASE = '/admin/merchant-onboarding/applications'

const loading = ref(false)
const applications = ref([])
const detailVisible = ref(false)
const detail = ref(null)
const attachments = ref([])

const reviewVisible = ref(false)
const current = ref(null)
const reviewing = ref(false)
const reviewForm = reactive({ storeName: '', rateBps: undefined, reason: '' })

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
  REJECTED: '已驳回',
  SUPPLEMENT_REQUIRED: '需补件',
  WITHDRAWN: '已撤回'
}[status] || status || '-')

const statusTag = status => ({
  APPROVED: 'success',
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

async function loadApplications() {
  loading.value = true
  try {
    const res = await request.get(BASE)
    applications.value = res.data || []
  } finally {
    loading.value = false
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
  reviewForm.reason = ''
  detailVisible.value = false
  reviewVisible.value = true
}

async function callReview(action, reason, extra = {}) {
  if (!current.value) return null
  reviewing.value = true
  try {
    const res = await request.post(`${BASE}/${current.value.id}/review`, {
      action,
      reason,
      ...extra
    })
    return res.data
  } finally {
    reviewing.value = false
  }
}

async function startReview() {
  try {
    await callReview('START_REVIEW', reviewForm.reason)
    ElMessage.success('已进入审核中')
    reviewVisible.value = false
    await loadApplications()
  } catch {
    // 请求层已提示
  }
}

async function approve() {
  try {
    const view = await callReview('APPROVE', reviewForm.reason, {
      storeName: reviewForm.storeName || undefined,
      rateBps: reviewForm.rateBps
    })
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
  try {
    const { value } = await ElMessageBox.prompt('请填写驳回原因，商家会在进度页看到该说明', '驳回申请', {
      inputValidator: input => Boolean(input && input.trim()) || '驳回原因必填',
      inputType: 'textarea'
    })
    await callReview('REJECT', value.trim())
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
  try {
    const { value } = await ElMessageBox.prompt('请填写需要补充的材料，商家可修改材料后重新提交', '要求补件', {
      inputValidator: input => Boolean(input && input.trim()) || '补件原因必填',
      inputType: 'textarea'
    })
    await callReview('SUPPLEMENT_REQUIRED', value.trim())
    ElMessage.success('已退回补件')
    reviewVisible.value = false
    await loadApplications()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      // 请求层已提示
    }
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

onMounted(loadApplications)
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
