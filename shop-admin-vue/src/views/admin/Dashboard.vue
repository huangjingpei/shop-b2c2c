<!--
文件：Dashboard.vue
用途：平台经营概览与订单类型展示。
维护注意：汇总取服务端statistics精确金额字符串，不回退意向总额；订单/分账状态不证明真实到账，未知退款及结算不得显示为零。列表金额是订单快照。
-->
<template>
  <div class="dashboard-page">
    <div class="page-title">
      <h2>平台全局监控大盘</h2>
      <p>查看商户、订单状态、已支付订单金额与预计佣金</p>
    </div>

    <!-- 核心指标卡片 -->
    <el-row :gutter="20" class="stat-cards">
      <el-col :span="6">
        <el-card shadow="hover" class="card-item stat-blue">
          <div class="stat-title">历史已支付订单金额</div>
          <div class="stat-value">￥{{ stats.paidTurnover }}</div>
          <div class="stat-tip">含退款状态订单，非退款净额</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="card-item stat-green">
          <div class="stat-title">未退款订单预计佣金</div>
          <div class="stat-value">￥{{ stats.estimatedCommission }}</div>
          <div class="stat-tip">订单快照估计，不是已结算收入</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="card-item stat-purple">
          <div class="stat-title">历史已支付订单数</div>
          <div class="stat-value">{{ stats.paidOrders }} 笔</div>
          <div class="stat-tip">含退款状态，不含待付款/关闭订单</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="card-item stat-orange">
          <div class="stat-title">在册入驻商户数</div>
          <div class="stat-value">{{ merchantCount ?? '—' }} 家</div>
          <div class="stat-tip">个人自然人免工商营业执照开店</div>
        </el-card>
      </el-col>
    </el-row>
    <el-alert :title="STATISTICS_NOTICE" type="info" :closable="false" style="margin-bottom: 16px;" />
    <p>待付款 {{ stats.pendingOrders }} 笔，意向金额 ￥{{ stats.pendingIntentAmount }}；退款状态 {{ stats.refundedOrders }} 笔。</p>
    <el-alert v-if="stats.unknownOrders > 0 || stats.amountAnomalyOrders > 0" :title="`需核对：未知状态 ${stats.unknownOrders} 单，异常金额 ${stats.amountAnomalyOrders} 单；异常金额未计入汇总。`" type="warning" :closable="false" />

    <!-- 最新交易与分账流水 -->
    <el-card shadow="never" class="table-card">
      <template #header>
        <div class="table-header">
          <span class="header-text">全平台最新订单与分账记录</span>
          <el-button size="small" type="primary" plain @click="loadData">刷新数据</el-button>
        </div>
      </template>

      <el-table :data="data.orders" stripe style="width: 100%" v-loading="loading">
        <el-table-column prop="orderSn" label="订单编号" width="220" />
        <el-table-column prop="buyerContact" label="买家联系方式" width="160" />
        <el-table-column label="商品类型" width="130">
          <template #default="{ row }">
            <el-tag :type="row.goodsType === 2 ? 'warning' : 'info'" size="small">
              {{ goodsTypeLabel(row.goodsType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="payAmount" label="订单金额快照" width="120">
          <template #default="{ row }">
            <span style="font-weight: 600; color: #0f172a;">￥{{ row.payAmount }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="commissionAmount" label="预计抽佣" width="120">
          <template #default="{ row }">
            <span style="color: #16a34a; font-weight: 600;">+￥{{ row.commissionAmount }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="merchantSettleAmount" label="预计商家净额" width="130">
          <template #default="{ row }">
            <span style="color: #2563eb;">￥{{ row.merchantSettleAmount }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="orderStatus" label="订单状态" width="120">
          <template #default="{ row }">
            <el-tag :type="getStatusTag(row.orderStatus)">{{ row.orderStatus }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sharingStatus" label="分账记录状态" width="160">
          <template #default="{ row }">
            <el-tag :type="row.sharingStatus === 'FINISHED' ? 'success' : 'warning'" effect="plain">
              {{ sharingRecordLabel(row.sharingStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="下单时间" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { goodsTypeLabel } from '../../utils/goodsType.mjs'
import { ref, computed, onMounted } from 'vue'
import request from '../../api/request'
import { statisticsView, STATISTICS_NOTICE, sharingRecordLabel } from '../../utils/orderStatistics.mjs'

const loading = ref(false)
const merchantCount = ref(null)
const data = ref({
  statistics: null,
  orders: []
})
const stats = computed(() => statisticsView(data.value.statistics))

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
  } catch {
    // 请求层提示错误，不记录包含认证配置的Axios异常。
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
