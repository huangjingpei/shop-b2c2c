<!--
文件：Finance.vue
用途：G31 财务复核与结算入口：待确认充值单、结算批次确认，并选择除自己以外的管理员作为复核人。
维护注意：
  1. 本页仅由 SUPERADMIN 路由进入；页面选择只是参数传递，后端仍以 SaToken 角色和 FinanceApprovalGuard 判定。
  2. 复核人候选来自 /admin/settlement/reviewers，已排除当前账号；严格模式无第二账号时显示“需至少分配两个财务账号”，不得用通用报错代替。
  3. 宽松模式允许不选复核人，提交后由后端按操作人落 SAME_OPERATOR；页面不得自行伪造 confirmedBy。
  4. 确认成功后刷新列表；金额、状态迁移以后端事务结果为准，不在前端预扣余额。
  5. 支付异常队列只读展示异常事件与已记录的人工处置结论；处置弹窗仅提交结论与说明，
     不改事件状态/资金标记、订单或退款账本，退款/补偿仍须走渠道或既有退款流程。
-->
<template>
  <div class="finance-page">
    <div class="page-header">
      <div>
        <h2>财务复核与结算</h2>
        <p>充值到账确认与结算批次确认；复核人仅可为除当前登录账号以外的启用管理员</p>
      </div>
      <el-button icon="Refresh" :loading="loading" @click="loadAll">刷新</el-button>
    </div>

    <el-alert v-if="reviewerPolicy.notice" :title="reviewerPolicy.notice" type="warning"
      :closable="false" show-icon class="policy-alert" />
    <el-alert v-else-if="reviewerPolicy.dualApprovalRequired"
      title="已开启双人复核：提交前必须选择另一位管理员作为复核人" type="info"
      :closable="false" show-icon class="policy-alert" />
    <el-alert v-else :title="reviewerPolicy.singleAdminNotice"
      type="warning" :closable="false" show-icon class="policy-alert" />

    <el-card shadow="never" class="table-card">
      <template #header>
        <div class="card-header">
          <span>待确认充值单</span>
          <span class="count">共 {{ recharges.length }} 笔</span>
        </div>
      </template>
      <el-table :data="recharges" stripe v-loading="loading">
        <el-table-column prop="rechargeNo" label="充值单号" min-width="160" />
        <el-table-column prop="merchantId" label="店铺ID" width="90" />
        <el-table-column label="申请金额" width="120">
          <template #default="{ row }">￥{{ row.amount ?? '-' }}</template>
        </el-table-column>
        <el-table-column prop="voucherNo" label="转账凭证号" min-width="140" />
        <el-table-column prop="payerName" label="付款人" width="120" />
        <el-table-column prop="requestedBy" label="申请人ID" width="100" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="openRechargeDialog(row)">确认到账</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!recharges.length" description="暂无待确认充值单" />
    </el-card>

    <el-card shadow="never" class="table-card">
      <template #header>
        <div class="card-header">
          <span>结算批次</span>
          <el-select v-model="batchMerchantId" placeholder="选择店铺后查询批次" style="width: 260px"
            filterable @change="loadBatches">
            <el-option v-for="store in merchants" :key="store.id"
              :label="`${store.storeName}（ID ${store.id}）`" :value="store.id" />
          </el-select>
        </div>
      </template>
      <el-table :data="batches" stripe v-loading="batchLoading">
        <el-table-column prop="batchNo" label="批次号" min-width="170" />
        <el-table-column prop="periodStart" label="账期开始" width="120" />
        <el-table-column prop="periodEnd" label="账期结束" width="120" />
        <el-table-column label="应收服务费" width="120">
          <template #default="{ row }">￥{{ row.netFeeAmount ?? '-' }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === 'CONFIRMED' ? 'success' : 'warning'" size="small">
              {{ row.status === 'CONFIRMED' ? '已确认' : '草稿' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="preparedBy" label="制单人ID" width="100" />
        <el-table-column prop="confirmedBy" label="确认人ID" width="100" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'DRAFT'" size="small" type="primary" link
              @click="openBatchDialog(row)">确认批次</el-button>
            <span v-else class="muted">已确认</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!batchMerchantId" description="请选择店铺查看结算批次" />
      <el-empty v-else-if="!batches.length" description="该店铺暂无结算批次" />
    </el-card>

    <el-card shadow="never" class="table-card">
      <template #header>
        <div class="card-header">
          <span>支付异常队列</span>
          <span class="count">需人工核对 · 共 {{ exceptions.length }} 条</span>
        </div>
      </template>
      <el-table :data="exceptions" stripe v-loading="exceptionLoading">
        <el-table-column prop="orderSn" label="订单号" min-width="150" />
        <el-table-column prop="merchantId" label="店铺ID" width="90" />
        <el-table-column prop="channel" label="渠道" width="90" />
        <el-table-column label="金额" width="120">
          <template #default="{ row }">
            ￥{{ row.payAmount ?? '-' }} {{ row.currency || '' }}
          </template>
        </el-table-column>
        <el-table-column prop="eventStatus" label="事件状态" width="140" />
        <el-table-column label="资金往来" width="100">
          <template #default="{ row }">{{ fundsLabel(row.fundsReceived) }}</template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="异常说明" min-width="180"
          show-overflow-tooltip />
        <el-table-column label="处置" width="230">
          <template #default="{ row }">
            <div v-if="row.dispositionAction" class="disposition-cell">
              <el-tag size="small" type="success">{{ actionLabel(row.dispositionAction) }}</el-tag>
              <span class="hint">{{ row.dispositionNote }}</span>
              <span class="hint">操作人 {{ row.disposedBy }} · {{ formatTime(row.disposedAt) }}</span>
            </div>
            <el-button v-else size="small" type="primary" link
              @click="openDispositionDialog(row)">记录处置</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!exceptions.length" description="暂无待核对的支付异常" />
    </el-card>

    <el-dialog v-model="rechargeDialogVisible" title="确认充值到账" width="480px" @closed="resetSelection">
      <el-form label-width="110px">
        <el-form-item label="充值单号">{{ rechargeTarget.rechargeNo }}</el-form-item>
        <el-form-item label="确认金额">
          <el-input-number v-model="rechargeAmount" :precision="2" :min="0.01" :step="10" />
          <span class="unit">元（以人工核对后的实际到账金额为准）</span>
        </el-form-item>
        <el-form-item label="复核人" :required="reviewerPolicy.dualApprovalRequired">
          <el-select v-model="selectedReviewer" clearable style="width: 100%"
            :placeholder="reviewerPlaceholder">
            <el-option v-for="item in reviewerPolicy.candidates" :key="item.id"
              :label="reviewerLabel(item)" :value="item.id" />
          </el-select>
          <div class="hint">候选已排除当前登录账号；最终是否允许同人由后端开关判定。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rechargeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="reviewerPolicy.blocked"
          @click="submitRecharge">确认入账</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="batchDialogVisible" title="确认结算批次" width="480px" @closed="resetSelection">
      <el-form label-width="110px">
        <el-form-item label="批次号">{{ batchTarget.batchNo || batchTarget.id }}</el-form-item>
        <el-form-item label="账期">
          {{ batchTarget.periodStart }} 至 {{ batchTarget.periodEnd }}
        </el-form-item>
        <el-form-item label="应收服务费">￥{{ batchTarget.netFeeAmount ?? '-' }}</el-form-item>
        <el-form-item label="复核人" :required="reviewerPolicy.dualApprovalRequired">
          <el-select v-model="selectedReviewer" clearable style="width: 100%"
            :placeholder="reviewerPlaceholder">
            <el-option v-for="item in reviewerPolicy.candidates" :key="item.id"
              :label="reviewerLabel(item)" :value="item.id" />
          </el-select>
          <div class="hint">确认后批次不可再重算；复核人不改变后端权限模型。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="reviewerPolicy.blocked"
          @click="submitBatch">确认批次</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dispositionDialogVisible" title="记录支付异常处置" width="520px"
      @closed="resetDisposition">
      <el-alert
        title="本操作只记录人工核对结论，不改写支付事件状态、资金标记、订单或退款账本；退款/补偿仍须走渠道或既有退款流程。"
        type="info" :closable="false" show-icon />
      <el-form label-width="100px" class="disposition-form">
        <el-form-item label="订单号">{{ dispositionTarget.orderSn || '-' }}</el-form-item>
        <el-form-item label="处置结论" required>
          <el-select v-model="dispositionAction" placeholder="请选择处置结论" style="width: 100%">
            <el-option v-for="item in exceptionActions" :key="item.value"
              :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="处置说明" required>
          <el-input v-model="dispositionNote" type="textarea" :rows="3" maxlength="200"
            show-word-limit placeholder="2-200 字，写明核对依据与结论" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dispositionDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="dispositionSubmitting"
          :disabled="!dispositionAction || !dispositionNote.trim()"
          @click="submitDisposition">提交处置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../../api/request'
import { reviewerState, validateReviewer } from '../../utils/financeReview.mjs'
import { sessionGet } from '../../utils/authSession.mjs'

// 会话隔离：超管页读超管桶（admin: 前缀键）。
const currentUserId = sessionGet(localStorage, 'userId', 'ADMIN') || ''
const loading = ref(false)
const batchLoading = ref(false)
const submitting = ref(false)
const recharges = ref([])
const merchants = ref([])
const batches = ref([])
const batchMerchantId = ref('')
const reviewers = ref([])
const dualApprovalRequired = ref(false)

// 异常队列与人工处置：结论白名单须与后端 PaymentExceptionDispositionService 保持一致。
const exceptionActions = [
  { value: 'REFUND_CONFIRMED', label: '退款已确认（渠道或既有退款流程）' },
  { value: 'DUPLICATE_RESOLVED', label: '重复付款已核销' },
  { value: 'CHANNEL_VERIFIED', label: '渠道流水已核对' },
  { value: 'NO_ACTION_REQUIRED', label: '核对后无需处理' }
]
const exceptionLoading = ref(false)
const exceptions = ref([])
const dispositionDialogVisible = ref(false)
const dispositionTarget = ref({})
const dispositionAction = ref('')
const dispositionNote = ref('')
const dispositionSubmitting = ref(false)

const fundsLabel = value => {
  if (value === 1) return '有渠道流水'
  if (value === 0) return '明确无'
  return '未判定'
}

const actionLabel = value => exceptionActions.find(item => item.value === value)?.label || value

const formatTime = value => (value ? String(value).replace('T', ' ').slice(0, 19) : '-')

const reviewerPolicy = computed(() => reviewerState({
  dualApprovalRequired: dualApprovalRequired.value,
  reviewers: reviewers.value,
  currentUserId
}))
const reviewerPlaceholder = computed(() => reviewerPolicy.value.dualApprovalRequired
  ? '必须选择复核人'
  : '可留空，按当前操作人记录同人复核')

const rechargeDialogVisible = ref(false)
const rechargeTarget = ref({})
const rechargeAmount = ref(0)
const batchDialogVisible = ref(false)
const batchTarget = ref({})
const selectedReviewer = ref('')

const reviewerLabel = item => `${item.realName || item.username || '管理员'}（${item.username}）`

const loadReviewers = async () => {
  const res = await request.get('/admin/settlement/reviewers')
  dualApprovalRequired.value = Boolean(res.data?.dualApprovalRequired)
  reviewers.value = Array.isArray(res.data?.reviewers) ? res.data.reviewers : []
}

const loadMerchants = async () => {
  const res = await request.get('/admin/merchants')
  merchants.value = Array.isArray(res.data) ? res.data : []
}

const loadRecharges = async () => {
  const res = await request.get('/admin/settlement/recharges/pending')
  recharges.value = Array.isArray(res.data) ? res.data : []
}

const loadExceptions = async () => {
  exceptionLoading.value = true
  try {
    const res = await request.get('/admin/settlement/payment-exceptions')
    exceptions.value = Array.isArray(res.data?.items) ? res.data.items : []
  } catch (error) {
    // 加载失败保留上次数据，避免把旧队列清空造成“已无异常”的误判。
    ElMessage.error(error.response?.data?.message || '支付异常队列加载失败')
  } finally {
    exceptionLoading.value = false
  }
}

const loadBatches = async () => {
  if (!batchMerchantId.value) {
    batches.value = []
    return
  }
  batchLoading.value = true
  try {
    const res = await request.get(`/admin/settlement/merchants/${batchMerchantId.value}/batches`)
    batches.value = Array.isArray(res.data) ? res.data : []
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '结算批次加载失败')
  } finally {
    batchLoading.value = false
  }
}

const loadAll = async () => {
  loading.value = true
  try {
    await Promise.all([loadReviewers(), loadMerchants(), loadRecharges(), loadExceptions()])
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '财务数据加载失败')
  } finally {
    loading.value = false
  }
}

const openRechargeDialog = row => {
  rechargeTarget.value = row
  rechargeAmount.value = Number(row.amount ?? 0)
  selectedReviewer.value = ''
  rechargeDialogVisible.value = true
}

const openBatchDialog = row => {
  batchTarget.value = row
  selectedReviewer.value = ''
  batchDialogVisible.value = true
}

const resetSelection = () => {
  selectedReviewer.value = ''
}

const openDispositionDialog = row => {
  dispositionTarget.value = row
  dispositionAction.value = ''
  dispositionNote.value = ''
  dispositionDialogVisible.value = true
}

const resetDisposition = () => {
  dispositionAction.value = ''
  dispositionNote.value = ''
}

const submitDisposition = async () => {
  if (dispositionSubmitting.value) return
  if (!dispositionAction.value) {
    ElMessage.warning('请选择处置结论')
    return
  }
  const note = dispositionNote.value.trim()
  if (note.length < 2 || note.length > 200) {
    ElMessage.warning('处置说明必须为 2-200 字')
    return
  }
  dispositionSubmitting.value = true
  try {
    await request.post(
      `/admin/settlement/payment-exceptions/${dispositionTarget.value.id}/disposition`,
      { action: dispositionAction.value, note }
    )
    ElMessage.success('处置结论已记录')
    dispositionDialogVisible.value = false
    await loadExceptions()
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '处置记录失败')
  } finally {
    dispositionSubmitting.value = false
  }
}

const guardSelection = () => {
  const result = validateReviewer({
    dualApprovalRequired: dualApprovalRequired.value,
    reviewers: reviewers.value,
    currentUserId,
    reviewerId: selectedReviewer.value
  })
  if (!result.ok) {
    ElMessage.warning(result.message)
    return null
  }
  return result
}

const submitRecharge = async () => {
  if (submitting.value) return
  const selection = guardSelection()
  if (!selection) return
  if (!(rechargeAmount.value > 0)) {
    ElMessage.warning('请输入人工核对后的实际到账金额')
    return
  }
  submitting.value = true
  try {
    const body = { confirmedAmount: rechargeAmount.value }
    if (selection.confirmedBy !== null) body.confirmedBy = selection.confirmedBy
    await request.post(`/admin/settlement/recharges/${rechargeTarget.value.rechargeNo}/confirm`, body)
    ElMessage.success('充值已确认入账')
    rechargeDialogVisible.value = false
    await loadRecharges()
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '充值确认失败')
  } finally {
    submitting.value = false
  }
}

const submitBatch = async () => {
  if (submitting.value) return
  const selection = guardSelection()
  if (!selection) return
  submitting.value = true
  try {
    const body = {}
    if (selection.confirmedBy !== null) body.confirmedBy = selection.confirmedBy
    await request.post(`/admin/settlement/batches/${batchTarget.value.id}/confirm`, body)
    ElMessage.success('结算批次已确认')
    batchDialogVisible.value = false
    await loadBatches()
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '结算批次确认失败')
  } finally {
    submitting.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.finance-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.page-header h2 {
  margin: 0;
  font-size: 20px;
  color: #0f172a;
}

.page-header p {
  margin: 4px 0 0;
  font-size: 13px;
  color: #64748b;
}

.policy-alert {
  margin-top: 4px;
}

.table-card {
  border-radius: 8px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.count {
  font-size: 12px;
  color: #64748b;
}

.hint {
  width: 100%;
  font-size: 11px;
  color: #64748b;
  line-height: 1.5;
}

.unit {
  margin-left: 8px;
  font-size: 12px;
  color: #64748b;
}

.muted {
  font-size: 12px;
  color: #94a3b8;
}

.disposition-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.disposition-form {
  margin-top: 12px;
}
</style>
