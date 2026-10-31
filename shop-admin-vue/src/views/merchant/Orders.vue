<template>
  <div class="merchant-orders">
    <div class="page-header">
      <div>
        <h2>本店订单履约与发货中心</h2>
        <p>实物订单录入物流单号发货，虚拟商品自动核销卡密流水实时追踪</p>
      </div>
      <el-button type="primary" plain @click="loadOrders">刷新订单列表</el-button>
    </div>

    <el-card shadow="never" class="table-card">
      <el-table :data="orders" stripe style="width: 100%" v-loading="loading">
        <el-table-column label="订单编号 / 时间" width="220">
          <template #default="{ row }">
            <div style="font-weight: 600; color: #1e293b;">{{ row.order.orderSn }}</div>
            <div style="font-size: 11px; color: #64748b;">{{ row.order.createdAt }}</div>
          </template>
        </el-table-column>
        <el-table-column label="购买商品明细" min-width="220">
          <template #default="{ row }">
            <div v-for="item in row.items" :key="item.id">
              <span style="font-weight: 500;">{{ item.productTitle }}</span>
              <span style="color: #64748b; font-size: 12px; margin-left: 6px;">{{ item.specInfo }} × {{ item.quantity }}</span>
              <div v-if="item.cardDetails" class="card-tag">
                <el-tag type="warning" size="small">已下发卡密</el-tag>
                <span class="card-text">{{ item.cardDetails }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="买家联系方式" width="160">
          <template #default="{ row }">
            <span>{{ row.order.buyerContact }}</span>
          </template>
        </el-table-column>
        <el-table-column label="金额与分成" width="160">
          <template #default="{ row }">
            <div>买家实付：<span style="font-weight: 600;">￥{{ row.order.payAmount }}</span></div>
            <div style="font-size: 12px; color: #2563eb;">净结收入：￥{{ row.order.merchantSettleAmount }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="getStatusTag(row.order.orderStatus)">{{ row.order.orderStatus }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.order.goodsType === 1 && row.order.orderStatus === 'PAID'"
              type="primary"
              size="small"
              @click="openDeliverDialog(row.order)"
            >
              录入发货
            </el-button>
            <span v-else-if="row.order.goodsType === 2" style="font-size: 12px; color: #16a34a;">已自动发货</span>
            <span v-else style="font-size: 12px; color: #64748b;">{{ row.order.logisticsCompany }} {{ row.order.trackingNumber }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 发货弹窗 -->
    <el-dialog v-model="deliverVisible" title="实物订单发货" width="440px">
      <el-form label-width="90px">
        <el-form-item label="快递公司" required>
          <el-select v-model="deliverForm.logisticsCompany" style="width: 100%;">
            <el-option label="顺丰速运" value="顺丰速运" />
            <el-option label="圆通速递" value="圆通速递" />
            <el-option label="中通快递" value="中通快递" />
            <el-option label="韵达速递" value="韵达速递" />
          </el-select>
        </el-form-item>
        <el-form-item label="快递单号" required>
          <el-input v-model="deliverForm.trackingNumber" placeholder="请输入运单号" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deliverVisible = false">取消</el-button>
        <el-button type="primary" @click="handleDeliver">确认发货</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../../api/request'

const loading = ref(false)
const orders = ref([])
const deliverVisible = ref(false)
const currentOrder = ref(null)

const deliverForm = ref({
  logisticsCompany: '顺丰速运',
  trackingNumber: ''
})

const getStatusTag = (status) => {
  switch (status) {
    case 'COMPLETED': return 'success'
    case 'PAID': return 'warning'
    case 'SHIPPED': return 'primary'
    default: return 'info'
  }
}

const loadOrders = async () => {
  loading.value = true
  try {
    const res = await request.get('/merchant/orders')
    orders.value = res.data
  } finally {
    loading.value = false
  }
}

const openDeliverDialog = (order) => {
  currentOrder.value = order
  deliverForm.value.trackingNumber = 'SF' + Math.floor(100000000000 + Math.random() * 900000000000)
  deliverVisible.value = true
}

const handleDeliver = async () => {
  if (!deliverForm.value.trackingNumber) {
    ElMessage.warning('请输入快递单号')
    return
  }
  await request.post(`/merchant/orders/${currentOrder.value.id}/deliver`, deliverForm.value)
  ElMessage.success('发货已完成')
  deliverVisible.value = false
  loadOrders()
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

.card-tag {
  margin-top: 4px;
}

.card-text {
  font-family: monospace;
  font-size: 11px;
  background: #fef3c7;
  color: #92400e;
  padding: 2px 6px;
  border-radius: 4px;
  margin-left: 6px;
}
</style>
