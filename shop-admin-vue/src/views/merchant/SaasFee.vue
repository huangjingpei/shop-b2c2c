<!--
文件：SaasFee.vue
用途：F10 商家端 SaaS 月费账单：展示本店各自然月固定技术服务费账单、免收原因与到期日（只读）。
维护注意：
 1. 本页与 F09 交易账单分开：这里只展示固定月度技术服务费，不展示成交额/抽佣；后端 /api/v1/merchant/settlement/saas-bills 固定本店。
 2. 缴费为线下转账人工确认，页面不提供在线支付；状态与金额以后端返回为准，前端不做换算。
 3. 账单由平台按月生成，同店同月重复生成幂等；页面无“重算”入口，避免商家改写已缴/免收记录。
-->
<template>
  <div class="saas-fee-page">
    <div class="page-header">
      <div>
        <h2>SaaS 月费账单</h2>
        <p>固定月度技术服务费，按自然月出账；与订单成交抽佣分开计算。缴费请线下转账后联系平台确认。</p>
      </div>
      <el-button icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-card shadow="never" class="table-card">
      <el-table :data="rows" stripe style="width: 100%" v-loading="loading">
        <el-table-column label="账期" width="120">
          <template #default="{ row }">{{ monthLabel(row.billingMonth) }}</template>
        </el-table-column>
        <el-table-column prop="billNo" label="账单号" min-width="180" />
        <el-table-column label="标准月费" width="110" align="right">
          <template #default="{ row }">{{ money(row.standardAmount) }}</template>
        </el-table-column>
        <el-table-column label="应收金额" width="110" align="right">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="dueDate" label="到期日" width="110" />
        <el-table-column label="免收原因" min-width="150">
          <template #default="{ row }">{{ row.waiveReason || '-' }}</template>
        </el-table-column>
        <el-table-column label="缴费信息" min-width="170">
          <template #default="{ row }">
            <span v-if="row.status === 'PAID'">{{ row.paidAt || '-' }} / 凭证 {{ row.voucherNo || '-' }}</span>
            <span v-else class="muted">待线下转账确认</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无 SaaS 月费账单，平台将在每个自然月结束后生成" />
        </template>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import request from '../../api/request'
import { monthLabel, statusLabel, statusTag } from '../../utils/saasFeeBill.mjs'

const loading = ref(false)
const rows = ref([])

const money = value => {
  const num = Number(value)
  return `￥${Number.isFinite(num) ? num.toFixed(2) : '0.00'}`
}

const load = async () => {
  loading.value = true
  try {
    const res = await request.get('/merchant/settlement/saas-bills', { params: { limit: 24 } })
    rows.value = Array.isArray(res.data) ? res.data : []
  } finally {
    loading.value = false
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
  max-width: 760px;
}
.table-card {
  border-radius: 8px;
}
.muted {
  color: #94a3b8;
}
</style>
