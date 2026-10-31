<template>
  <div class="orders-page">
    <div class="page-header">
      <div>
        <h2>全平台交易订单与分账监察</h2>
        <p>全平台订单交易状态、二级分账流水跟踪与资金零接触稽核</p>
      </div>
      <el-button type="primary" plain @click="loadOrders">刷新订单流水</el-button>
    </div>

    <el-card shadow="never" class="table-card">
      <el-table :data="orders" stripe style="width: 100%" v-loading="loading">
        <el-table-column prop="orderSn" label="订单号" width="220" />
        <el-table-column prop="merchantId" label="所属店铺ID" width="110" />
        <el-table-column prop="buyerContact" label="买家联系手机/邮箱" width="160" />
        <el-table-column label="商品类型" width="130">
          <template #default="{ row }">
            <el-tag :type="row.goodsType === 2 ? 'warning' : 'info'" size="small">
              {{ row.goodsType === 2 ? '⚡ 虚拟卡密' : '📦 实物物流' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="payAmount" label="支付金额" width="120">
          <template #default="{ row }">￥{{ row.payAmount }}</template>
        </el-table-column>
        <el-table-column prop="commissionAmount" label="平台抽成" width="110">
          <template #default="{ row }">
            <span style="color: #16a34a; font-weight: 600;">+￥{{ row.commissionAmount }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="merchantSettleAmount" label="商户实收" width="120">
          <template #default="{ row }">￥{{ row.merchantSettleAmount }}</template>
        </el-table-column>
        <el-table-column prop="orderStatus" label="订单状态" width="110">
          <template #default="{ row }">
            <el-tag>{{ row.orderStatus }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sharingStatus" label="二级分账" width="120">
          <template #default="{ row }">
            <el-tag :type="row.sharingStatus === 'FINISHED' ? 'success' : 'warning'">
              {{ row.sharingStatus === 'FINISHED' ? '分账完毕' : '待分账' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="下单时间" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '../../api/request'

const loading = ref(false)
const orders = ref([])

const loadOrders = async () => {
  loading.value = true
  try {
    const res = await request.get('/admin/orders')
    orders.value = res.data.orders
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadOrders()
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

.table-card {
  border-radius: 8px;
}
</style>
