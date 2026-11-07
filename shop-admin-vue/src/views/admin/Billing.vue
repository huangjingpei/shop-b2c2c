<!--
文件：Billing.vue
用途：F09 平台商户账单治理：全平台账单查询（可按店铺过滤）、指定周期重算与 CSV 导出。
维护注意：
 1. 页面只装配查询参数，授权由后端 /api/v1/admin/** 强制 SUPERADMIN；本地筛选不是权限边界。
 2. 重算为聚合覆盖（upsert 按 店铺+周期+类型 唯一键），不触碰结算批次锁定语义；重复生成结果幂等。
 3. 金额口径：fee_amt 为净扣收服务费（计费服务－退款返还，可为负）；规则与 utils/billingStatement.mjs、后端 BillingStatementService 一致。
 4. 重算提交 shopId 必填正整数、periodType 白名单 DAILY/MONTHLY、periodDate yyyy-MM-dd；非法输入由 regeneratePayload 先拒绝。
-->
<template>
  <div class="billing-page">
    <div class="page-header">
      <div>
        <h2>商户账单治理</h2>
        <p>全平台商户日/月账单查询、按周期重算与 CSV 导出；重算为幂等聚合覆盖，可随时修正</p>
      </div>
      <el-button type="warning" icon="Refresh" @click="openRegenerate">重算账单</el-button>
    </div>

    <el-card shadow="never" class="filter-card">
      <el-form inline @submit.prevent>
        <el-form-item label="店铺ID">
          <el-input v-model="filters.shopId" placeholder="按店铺筛选" clearable style="width: 140px" @change="reload" />
        </el-form-item>
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
        <el-table-column prop="shopId" label="店铺ID" width="90" />
        <el-table-column prop="storeName" label="店铺名称" min-width="140" show-overflow-tooltip />
        <el-table-column label="成交总额" width="120" align="right">
          <template #default="{ row }">{{ money(row.gmv) }}</template>
        </el-table-column>
        <el-table-column label="其中代付" width="120" align="right">
          <template #default="{ row }">{{ money(row.peerPayGmv) }}</template>
        </el-table-column>
        <el-table-column label="订单数" width="90" align="right">
          <template #default="{ row }">{{ row.ordersCnt }}</template>
        </el-table-column>
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
        <template #empty><el-empty description="暂无账单，可用右上角重算生成指定周期账单" /></template>
      </el-table>
      <div class="pager">
        <el-pagination
          background layout="total, prev, pager, next" :total="total"
          :current-page="page" :page-size="size"
          @current-change="p => { page = p; load() }" />
      </div>
    </el-card>

    <!-- 重算 -->
    <el-dialog v-model="regenDialog" title="重算商户账单" width="460px">
      <p class="dialog-tip">按「店铺 + 周期 + 类型」重算账单：重新聚合成交、退款与服务费并覆盖原账单，重复执行结果一致。</p>
      <el-form label-width="90px">
        <el-form-item label="店铺ID" required>
          <el-input v-model="regenForm.shopId" placeholder="必填，店铺主键ID" style="width: 200px" />
        </el-form-item>
        <el-form-item label="账单类型" required>
          <el-select v-model="regenForm.periodType" style="width: 200px">
            <el-option value="DAILY" label="日账单" />
            <el-option value="MONTHLY" label="月账单" />
          </el-select>
        </el-form-item>
        <el-form-item label="账单日期" required>
          <el-date-picker v-model="regenForm.periodDate" type="date" value-format="YYYY-MM-DD"
                          placeholder="日账单任选当天；月账单任取当月" style="width: 200px" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="regenDialog = false">取消</el-button>
        <el-button type="warning" :loading="submitting" @click="submitRegenerate">确认重算</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../../api/request'
import {
  billingCsvFilename, billingQuery, billingStatusLabel, billingStatusTag, periodTypeLabel, regeneratePayload
} from '../../utils/billingStatement.mjs'

const loading = ref(false)
const exporting = ref(false)
const submitting = ref(false)
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

const filters = reactive({ shopId: '', periodType: 'DAILY', ...defaultRange('DAILY') })

const regenDialog = ref(false)
const regenForm = reactive({ shopId: '', periodType: 'DAILY', periodDate: '' })

const money = value => {
  const num = Number(value)
  return Number.isFinite(num) ? num.toFixed(2) : '0.00'
}

const buildQuery = () => {
  const query = billingQuery({
    periodType: filters.periodType,
    from: filters.from,
    to: filters.to,
    page: page.value,
    size: size.value
  })
  const shopId = String(filters.shopId ?? '').trim()
  if (shopId) {
    if (!/^[1-9]\d*$/.test(shopId)) throw new Error('店铺ID须为正整数')
    query.shopId = shopId
  }
  return query
}

const load = async () => {
  let params
  try { params = buildQuery() } catch (err) { ElMessage.warning(err.message); return }
  loading.value = true
  try {
    const res = await request.get('/admin/billing/statements', { params })
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
    const blob = await request.get('/admin/billing/statements/export', { params, responseType: 'blob' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = billingCsvFilename(filters.shopId || null, filters.periodType, filters.from, filters.to)
    link.click()
    URL.revokeObjectURL(url)
    ElMessage.success('账单已导出')
  } finally {
    exporting.value = false
  }
}

const openRegenerate = () => {
  regenForm.shopId = ''
  regenForm.periodType = 'DAILY'
  regenForm.periodDate = ''
  regenDialog.value = true
}

const submitRegenerate = async () => {
  let body
  try { body = regeneratePayload(regenForm) } catch (err) { ElMessage.warning(err.message); return }
  submitting.value = true
  try {
    const res = await request.post('/admin/billing/statements/regenerate', body)
    ElMessage.success(`已重算：成交 ${money(res.data.gmv)}，净扣收 ${money(res.data.feeAmt)}`)
    regenDialog.value = false
    load()
  } finally {
    submitting.value = false
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
.dialog-tip {
  margin: 0 0 12px;
  font-size: 13px;
  color: #475569;
}
</style>
