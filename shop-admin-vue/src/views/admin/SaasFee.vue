<!--
文件：SaasFee.vue
用途：F10 超管 SaaS 月费账单治理：账单查询、单店月账单生成、线下缴费复核确认与逾期扫描。
维护注意：
 1. 仅 SUPERADMIN 路由进入；后端 /api/v1/admin/** 再次校验角色，页面筛选不是授权边界。
 2. 生成只允许已结束自然月，同店同月幂等返回既有账单；本页不提供修改金额/状态入口，应收金额只能来自店铺月费配置。
 3. 缴费确认必须填写线下凭证号；复核人是否必填由 /admin/settlement/reviewers 的 dualApprovalRequired 与后端 FinanceApprovalGuard 判定，前端不得伪造 confirmedBy。
 4. 逾期扫描只把待缴且过到期日的账单置 OVERDUE 并发站内信，不自动停店/停服、不改 merchant_store.fee_status。
-->
<template>
  <div class="saas-admin-page">
    <div class="page-header">
      <div>
        <h2>SaaS 月费账单治理</h2>
        <p>固定月度技术服务费账单，与交易抽佣/结算批次分离；缴费为线下转账人工确认，逾期只改账单状态。</p>
      </div>
      <div>
        <el-button :loading="scanning" @click="scanOverdue">逾期扫描</el-button>
        <el-button type="primary" icon="Plus" @click="openGenerate">生成月账单</el-button>
      </div>
    </div>

    <el-card shadow="never" class="filter-card">
      <el-form inline @submit.prevent>
        <el-form-item label="店铺">
          <el-select v-model="filters.merchantId" placeholder="全部店铺" clearable filterable style="width: 220px">
            <el-option v-for="store in merchants" :key="store.id"
              :label="`${store.storeName}（ID ${store.id}）`" :value="store.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filters.status" placeholder="全部状态" clearable style="width: 140px">
            <el-option v-for="status in BILL_STATUSES" :key="status"
              :label="statusLabel(status)" :value="status" />
          </el-select>
        </el-form-item>
        <el-form-item label="账期">
          <el-date-picker v-model="filters.month" type="month" value-format="YYYY-MM"
            placeholder="全部账期" style="width: 150px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" @click="reload">查询</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="table-card">
      <el-table :data="rows" stripe style="width: 100%" v-loading="loading">
        <el-table-column label="店铺" min-width="170">
          <template #default="{ row }">{{ storeLabel(row.merchantId) }}</template>
        </el-table-column>
        <el-table-column label="账期" width="110">
          <template #default="{ row }">{{ monthLabel(row.billingMonth) }}</template>
        </el-table-column>
        <el-table-column prop="billNo" label="账单号" min-width="170" />
        <el-table-column label="标准月费" width="105" align="right">
          <template #default="{ row }">{{ money(row.standardAmount) }}</template>
        </el-table-column>
        <el-table-column label="应收" width="100" align="right">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="95">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="dueDate" label="到期日" width="105" />
        <el-table-column label="免收/缴费" min-width="190">
          <template #default="{ row }">
            <span v-if="row.status === 'WAIVED'">{{ row.waiveReason || '免收' }}</span>
            <span v-else-if="row.status === 'PAID'">
              {{ row.paidAt || '-' }} / 凭证 {{ row.voucherNo || '-' }} / 复核 {{ row.confirmedBy || '-' }}
            </span>
            <span v-else class="muted">待线下转账确认</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'PENDING' || row.status === 'OVERDUE'"
              size="small" type="primary" link @click="openPay(row)">确认缴费</el-button>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无 SaaS 月费账单" /></template>
      </el-table>
      <div class="pager">
        <el-button size="small" :disabled="page <= 1" @click="prevPage">上一页</el-button>
        <span class="pager-info">第 {{ page }} 页</span>
        <el-button size="small" :disabled="rows.length < size" @click="nextPage">下一页</el-button>
      </div>
    </el-card>

    <el-dialog v-model="generateVisible" title="生成 SaaS 月账单" width="480px">
      <el-form label-width="100px">
        <el-form-item label="店铺" required>
          <el-select v-model="generateForm.merchantId" filterable placeholder="选择店铺" style="width: 100%">
            <el-option v-for="store in merchants" :key="store.id"
              :label="`${store.storeName}（ID ${store.id}）`" :value="store.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="账期月份" required>
          <el-date-picker v-model="generateForm.month" type="month" value-format="YYYY-MM"
            placeholder="只能选择已结束月份" style="width: 100%" />
        </el-form-item>
        <div class="hint">同店同月已存在时不会重算，直接返回既有账单；免收规则由后端按 TRIAL/EXEMPTED/免费期判定。</div>
      </el-form>
      <template #footer>
        <el-button @click="generateVisible = false">取消</el-button>
        <el-button type="primary" :loading="generating" @click="submitGenerate">生成</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="payVisible" title="确认 SaaS 月费线下缴费" width="520px" @closed="resetPay">
      <el-form label-width="110px">
        <el-form-item label="账单">{{ payTarget.billNo }}（{{ monthLabel(payTarget.billingMonth) }}）</el-form-item>
        <el-form-item label="应收金额">￥{{ payTarget.amount }}</el-form-item>
        <el-form-item label="缴费方式">
          <el-select v-model="payForm.paymentMethod" style="width: 100%">
            <el-option v-for="method in PAYMENT_METHODS" :key="method" label="线下转账" :value="method" />
          </el-select>
        </el-form-item>
        <el-form-item label="转账凭证号" required>
          <el-input v-model="payForm.voucherNo" maxlength="64" show-word-limit placeholder="银行流水号/转账凭证号" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="payForm.remark" type="textarea" maxlength="255" show-word-limit :rows="2" />
        </el-form-item>
        <el-form-item label="复核人" :required="reviewerPolicy.dualApprovalRequired">
          <el-select v-model="payForm.confirmedBy" clearable style="width: 100%" :placeholder="reviewerPlaceholder">
            <el-option v-for="item in reviewerPolicy.candidates" :key="item.id"
              :label="reviewerLabel(item)" :value="item.id" />
          </el-select>
          <div class="hint">候选已排除当前登录账号；最终是否允许同人由后端开关判定。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="payVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="reviewerPolicy.blocked"
          @click="submitPay">确认缴费</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../../api/request'
import { sessionGet } from '../../utils/authSession.mjs'
import { reviewerState, validateReviewer } from '../../utils/financeReview.mjs'
import {
  BILL_STATUSES, PAYMENT_METHODS,
  generateBillPayload, monthLabel, paymentPayload, saasBillQuery, statusLabel, statusTag
} from '../../utils/saasFeeBill.mjs'

const currentUserId = sessionGet(localStorage, 'userId', 'ADMIN') || ''
const loading = ref(false)
const scanning = ref(false)
const submitting = ref(false)
const generating = ref(false)
const rows = ref([])
const merchants = ref([])
const reviewers = ref([])
const dualApprovalRequired = ref(false)

const page = ref(1)
const size = 50
const filters = reactive({ merchantId: null, status: null, month: null })

const generateVisible = ref(false)
const generateForm = reactive({ merchantId: null, month: '' })
const payVisible = ref(false)
const payTarget = ref({})
const payForm = reactive({ paymentMethod: 'OFFLINE', voucherNo: '', remark: '', confirmedBy: null })

const reviewerPolicy = computed(() => reviewerState({
  dualApprovalRequired: dualApprovalRequired.value,
  reviewers: reviewers.value,
  currentUserId
}))
const reviewerPlaceholder = computed(() => reviewerPolicy.value.dualApprovalRequired
  ? '必须选择复核人'
  : '可留空，按当前操作人记录同人复核')

const reviewerLabel = item => `${item.realName || item.username || '管理员'}（${item.username}）`
const storeLabel = id => {
  const store = merchants.value.find(item => String(item.id) === String(id))
  return store ? `${store.storeName}（ID ${store.id}）` : `店铺 ${id}`
}
const money = value => {
  const num = Number(value)
  return `￥${Number.isFinite(num) ? num.toFixed(2) : '0.00'}`
}

const loadMerchants = async () => {
  const res = await request.get('/admin/merchants')
  merchants.value = Array.isArray(res.data) ? res.data : []
}

const loadReviewers = async () => {
  const res = await request.get('/admin/settlement/reviewers')
  dualApprovalRequired.value = Boolean(res.data?.dualApprovalRequired)
  reviewers.value = Array.isArray(res.data?.reviewers) ? res.data.reviewers : []
}

const load = async () => {
  let params
  try {
    params = saasBillQuery({
      merchantId: filters.merchantId,
      status: filters.status,
      month: filters.month,
      limit: size,
      offset: (page.value - 1) * size
    })
  } catch (err) {
    ElMessage.warning(err.message)
    return
  }
  loading.value = true
  try {
    const res = await request.get('/admin/settlement/saas-bills', { params })
    rows.value = Array.isArray(res.data?.items) ? res.data.items : []
  } finally {
    loading.value = false
  }
}

const reload = () => { page.value = 1; load() }
const prevPage = () => { if (page.value > 1) { page.value -= 1; load() } }
const nextPage = () => { page.value += 1; load() }

const scanOverdue = async () => {
  scanning.value = true
  try {
    const res = await request.post('/admin/settlement/saas-bills/overdue-scan')
    ElMessage.success(`逾期扫描完成，本次置位 ${res.data?.changed ?? 0} 条`)
    load()
  } finally {
    scanning.value = false
  }
}

const openGenerate = () => {
  generateForm.merchantId = filters.merchantId || null
  generateForm.month = filters.month || ''
  generateVisible.value = true
}

const submitGenerate = async () => {
  let payload
  try {
    payload = generateBillPayload(generateForm)
  } catch (err) {
    ElMessage.warning(err.message)
    return
  }
  generating.value = true
  try {
    await request.post(`/admin/settlement/merchants/${payload.merchantId}/saas-fee-bills/generate`,
      null, { params: { month: payload.month } })
    ElMessage.success('月账单已生成或已存在')
    generateVisible.value = false
    reload()
  } finally {
    generating.value = false
  }
}

const openPay = row => {
  payTarget.value = { ...row }
  payForm.paymentMethod = 'OFFLINE'
  payForm.voucherNo = ''
  payForm.remark = ''
  payForm.confirmedBy = null
  payVisible.value = true
}

const resetPay = () => {
  payTarget.value = {}
}

const submitPay = async () => {
  let payload
  try {
    payload = paymentPayload(payForm)
  } catch (err) {
    ElMessage.warning(err.message)
    return
  }
  const check = validateReviewer({
    dualApprovalRequired: dualApprovalRequired.value,
    reviewers: reviewers.value,
    currentUserId,
    reviewerId: payload.confirmedBy
  })
  if (!check.ok) {
    ElMessage.warning(check.message)
    return
  }
  submitting.value = true
  try {
    await request.post(`/admin/settlement/saas-bills/${payTarget.value.billNo}/confirm`, {
      paymentMethod: payload.paymentMethod,
      voucherNo: payload.voucherNo,
      remark: payload.remark,
      confirmedBy: check.confirmedBy
    })
    ElMessage.success('SaaS 月费已确认缴费')
    payVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadMerchants(), loadReviewers()])
  load()
})
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
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
.filter-card {
  margin-bottom: 16px;
  border-radius: 8px;
}
.table-card {
  border-radius: 8px;
}
.pager {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
}
.pager-info {
  font-size: 13px;
  color: #64748b;
}
.hint {
  font-size: 12px;
  color: #94a3b8;
  line-height: 1.5;
}
.muted {
  color: #94a3b8;
}
</style>
