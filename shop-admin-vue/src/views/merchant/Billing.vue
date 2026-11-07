<!--
文件：Billing.vue
用途：F09 商家端本店账单：日/月账单分页查询、金额口径展示与 CSV 导出。
维护注意：
 1. 页面只装配查询参数，授权由后端 /api/v1/merchant/** 强制 MERCHANT_ADMIN/SUPERADMIN 且固定本店 shopId，本地筛选不是权限边界。
 2. 金额口径：gmv=成交总额，peer_pay_gmv/direct_pay_gmv=代付/自付拆分，refund_amt=退款，fee_amt=净扣收服务费（可为负）；
    规则与 utils/billingStatement.mjs、后端 BillingStatementService 一致，页面不做本地换算。
 3. 导出为后端 CSV（Blob 下载，含公式注入防护），跨度上限：日账单 92 天、月账单 24 个月，超限由规则模块先拒绝。
-->
<template>
  <div class="billing-page">
    <div class="page-header">
      <div>
        <h2>本店账单</h2>
        <p>按成交口径生成的日/月账单，含交易额、代付拆分、退款与净扣收服务费，可导出 CSV 对账</p>
      </div>
    </div>

    <el-card shadow="never" class="filter-card">
      <el-form inline @submit.prevent>
        <el-form-item label="账单类型">
          <el-select v-model="filters.periodType" style="width: 130px" @change="onPeriodTypeChange">
            <el-option value="DAILY" label="日账单" />
            <el-option value="MONTHLY" label="月账单" />
          </el-select>
        </el-form-item>
        <el-form-item label="开始日期">
          <el-date-picker v-model="filters.from" type="date" value-format="YYYY-MM-DD" placeholder="开始日期" style="width: 150px" />
        </el-form-item>
        <el-form-item label="结束日期">
          <el-date-picker v-model="filters.to" type="date" value-format="YYYY-MM-DD" placeholder="结束日期" style="width: 150px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" @click="reload">查询</el-button>
          <el-button icon="Download" :loading="exporting" @click="exportCsv">导出 CSV</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="table-card">
      <el-table :data="rows" stripe style="width: 100%" v-loading="loading">
        <el-table-column prop="periodDate" label="账单周期" width="110" />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">{{ periodTypeLabel(row.periodType) }}</template>
        </el-table-column>
        <el-table-column label="成交总额" width="120" align="right">
          <template #default="{ row }">{{ money(row.gmv) }}</template>
        </el-table-column>
        <el-table-column label="其中代付" width="120" align="right">
          <template #default="{ row }">{{ money(row.peerPayGmv) }}</template>
        </el-table-column>
        <el-table-column label="其中自付" width="120" align="right">
          <template #default="{ row }">{{ money(row.directPayGmv) }}</template>
        </el-table-column>
        <el-table-column prop="ordersCnt" label="订单数" width="90" align="right" />
        <el-table-column label="退款金额" width="120" align="right">
          <template #default="{ row }">{{ money(row.refundAmt) }}</template>
        </el-table-column>
        <el-table-column label="净扣收服务费" width="130" align="right">
          <template #default="{ row }">
            <span :class="{ negative: Number(row.feeAmt) < 0 }">{{ money(row.feeAmt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag :type="billingStatusTag(row.status)" effect="light">{{ billingStatusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无账单，账单由系统每日生成或联系平台重算" /></template>
      </el-table>
      <div class="pager">
        <el-pagination
          background layout="total, prev, pager, next" :total="total"
          :current-page="page" :page-size="size"
          @current-change="p => { page = p; load() }" />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../../api/request'
import {
  billingCsvFilename, billingQuery, billingStatusLabel, billingStatusTag, periodTypeLabel
} from '../../utils/billingStatement.mjs'

const loading = ref(false)
const exporting = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)

const defaultRange = periodType => {
  const to = new Date()
  const from = new Date()
  if (periodType === 'MONTHLY') from.setMonth(from.getMonth() - 5)
  else from.setDate(from.getDate() - 29)
  const fmt = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  return { from: fmt(from), to: fmt(to) }
}

const filters = reactive({ periodType: 'DAILY', ...defaultRange('DAILY') })

const money = value => {
  const num = Number(value)
  return Number.isFinite(num) ? num.toFixed(2) : '0.00'
}

const buildQuery = () => billingQuery({
  periodType: filters.periodType,
  from: filters.from,
  to: filters.to,
  page: page.value,
  size: size.value
})

const load = async () => {
  let params
  try { params = buildQuery() } catch (err) { ElMessage.warning(err.message); return }
  loading.value = true
  try {
    const res = await request.get('/merchant/billing/statements', { params })
    rows.value = res.data.list
    total.value = Number(res.data.total)
  } finally {
    loading.value = false
  }
}

const reload = () => { page.value = 1; load() }

const onPeriodTypeChange = type => {
  const range = defaultRange(type)
  filters.from = range.from
  filters.to = range.to
  reload()
}

const exportCsv = async () => {
  let params
  try { params = buildQuery() } catch (err) { ElMessage.warning(err.message); return }
  exporting.value = true
  try {
    const blob = await request.get('/merchant/billing/statements/export', { params, responseType: 'blob' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = billingCsvFilename(null, filters.periodType, filters.from, filters.to)
    link.click()
    URL.revokeObjectURL(url)
    ElMessage.success('账单已导出')
  } finally {
    exporting.value = false
  }
}

onMounted(load)
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
}
.negative {
  color: #dc2626;
}
</style>
