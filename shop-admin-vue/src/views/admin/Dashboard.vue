<template>
  <div class="dashboard-page">
    <div class="page-title">
      <h2>平台全局监控大盘</h2>
      <p>实时监控入驻商户经营状态、交易流水与官方二级分账提成</p>
    </div>

    <!-- 核心指标卡片 -->
    <el-row :gutter="20" class="stat-cards">
      <el-col :span="6">
        <el-card shadow="hover" class="card-item stat-blue">
          <div class="stat-title">全平台累计交易额 (GMV)</div>
          <div class="stat-value">￥{{ data.totalTurnover || '0.00' }}</div>
          <div class="stat-tip">微信/支付宝专户存管，平台零碰钱</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="card-item stat-green">
          <div class="stat-title">平台技术服务提成 (合规净收入)</div>
          <div class="stat-value">￥{{ data.totalCommission || '0.00' }}</div>
          <div class="stat-tip">按平均 3.00% 自动二级分账结算</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="card-item stat-purple">
          <div class="stat-title">总交易订单量</div>
          <div class="stat-value">{{ data.totalOrders || 0 }} 笔</div>
          <div class="stat-tip">单店独立结算履约闭环</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="card-item stat-orange">
          <div class="stat-title">在册入驻商户数</div>
          <div class="stat-value">{{ merchantCount }} 家</div>
          <div class="stat-tip">个人自然人免工商营业执照开店</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 最新交易与分账流水 -->
    <el-card shadow="never" class="table-card">
      <template #header>
        <div class="table-header">
          <span class="header-text">全平台最新交易与分账流水</span>
          <el-button size="small" type="primary" plain @click="loadData">刷新数据</el-button>
        </div>
      </template>

      <el-table :data="data.orders" stripe style="width: 100%" v-loading="loading">
        <el-table-column prop="orderSn" label="订单编号" width="220" />
        <el-table-column prop="buyerContact" label="买家联系方式" width="160" />
        <el-table-column label="商品类型" width="130">
          <template #default="{ row }">
            <el-tag :type="row.goodsType === 2 ? 'warning' : 'info'" size="small">
              {{ row.goodsType === 2 ? '⚡ 虚拟卡密(秒发)' : '📦 实物物流' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="payAmount" label="实付总额" width="120">
          <template #default="{ row }">
            <span style="font-weight: 600; color: #0f172a;">￥{{ row.payAmount }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="commissionAmount" label="平台抽佣" width="120">
          <template #default="{ row }">
            <span style="color: #16a34a; font-weight: 600;">+￥{{ row.commissionAmount }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="merchantSettleAmount" label="商家应结净款" width="130">
          <template #default="{ row }">
            <span style="color: #2563eb;">￥{{ row.merchantSettleAmount }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="orderStatus" label="订单状态" width="120">
          <template #default="{ row }">
            <el-tag :type="getStatusTag(row.orderStatus)">{{ row.orderStatus }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sharingStatus" label="二级分账状态" width="140">
          <template #default="{ row }">
            <el-tag :type="row.sharingStatus === 'FINISHED' ? 'success' : 'warning'" effect="plain">
              {{ row.sharingStatus === 'FINISHED' ? '已自动划拨' : '待结算' }}
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
const merchantCount = ref(1)
const data = ref({
  totalTurnover: '0.00',
  totalCommission: '0.00',
  totalOrders: 0,
  orders: []
})

const getStatusTag = (status) => {
  switch (status) {
    case 'COMPLETED': return 'success'
    case 'PAID': return 'primary'
    case 'SHIPPED': return 'warning'
    default: return 'info'
  }
}

const loadData = async () => {
  loading.value = true
  try {
    const res = await request.get('/admin/orders')
    data.value = res.data

    const mRes = await request.get('/admin/merchants')
    merchantCount.value = mRes.data.length
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.page-title h2 {
  margin: 0;
  font-size: 20px;
  color: #0f172a;
}

.page-title p {
  margin: 4px 0 20px;
  font-size: 13px;
  color: #64748b;
}

.stat-cards {
  margin-bottom: 24px;
}

.card-item {
  border-radius: 8px;
  border: none;
}

.stat-title {
  font-size: 13px;
  color: #64748b;
  margin-bottom: 8px;
}

.stat-value {
  font-size: 26px;
  font-weight: 700;
  color: #0f172a;
  margin-bottom: 6px;
}

.stat-tip {
  font-size: 11px;
  color: #94a3b8;
}

.stat-blue { border-left: 4px solid #3b82f6; }
.stat-green { border-left: 4px solid #10b981; }
.stat-purple { border-left: 4px solid #8b5cf6; }
.stat-orange { border-left: 4px solid #f97316; }

.table-card {
  border-radius: 8px;
}

.table-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-text {
  font-size: 15px;
  font-weight: 600;
  color: #0f172a;
}
</style>
