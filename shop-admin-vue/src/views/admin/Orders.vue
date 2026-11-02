<template>
  <div class="orders-page">
    <div class="page-header">
      <div>
        <h2>全平台交易订单与代付监察大盘</h2>
        <p>全平台订单穿透审计、代付三方角色对账、二级分账流水跟踪与资金安全监管</p>
      </div>
      <el-button type="primary" plain @click="loadOrders">刷新订单流水</el-button>
    </div>

    <!-- 顶部聚合统计指标卡片 -->
    <el-row :gutter="16" class="stats-row">
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">平台全网总订单</div>
          <div class="stat-val">{{ totalOrders }} <span class="stat-unit">笔</span></div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">全网成交 GMV 流水</div>
          <div class="stat-val text-rose">￥{{ Number(totalTurnover).toFixed(2) }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">平台技术抽成收益</div>
          <div class="stat-val text-green">+￥{{ Number(totalCommission).toFixed(2) }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">代付订单总数 (社交增长)</div>
          <div class="stat-val text-blue">{{ peerPayCount }} <span class="stat-unit">笔</span></div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="table-card">
      <el-table :data="orders" stripe style="width: 100%" v-loading="loading">
        <!-- 订单号与时间 -->
        <el-table-column label="订单号 / 时间" width="220">
          <template #default="{ row }">
            <div style="font-weight: 600; color: #1e293b;">{{ row.orderSn }}</div>
            <div style="font-size: 11px; color: #64748b;">{{ row.createdAt }}</div>
            <div style="font-size: 10px; color: #94a3b8; margin-top: 2px;">店铺ID: {{ row.merchantId }}</div>
          </template>
        </el-table-column>

        <!-- 代付类型与两段式锁状态 -->
        <el-table-column label="支付/代付模式" width="150">
          <template #default="{ row }">
            <div>
              <el-tag v-if="row.peerPayType === 2" type="danger" size="small" effect="dark">
                ★ 卖家代客推单
              </el-tag>
              <el-tag v-else-if="row.peerPayType === 1" type="warning" size="small">
                🤝 买家求助代付
              </el-tag>
              <el-tag v-else type="info" size="small">
                🛒 自主支付
              </el-tag>
            </div>
            <div style="margin-top: 4px;">
              <el-tag v-if="row.peerStatus === 'RESERVED_LOCK'" type="danger" size="small" effect="plain">
                ⏱ 15m已锁库
              </el-tag>
              <el-tag v-else-if="row.peerStatus === 'PAID'" type="success" size="small" effect="plain">
                ✓ 代付已结
              </el-tag>
              <el-tag v-else-if="row.peerStatus === 'INIT'" type="info" size="small" effect="plain">
                意向未锁
              </el-tag>
            </div>
          </template>
        </el-table-column>

        <!-- 三方不可篡改对账关系：买家 vs 付款人 -->
        <el-table-column label="三方穿透角色 (买家/付款人)" width="210">
          <template #default="{ row }">
            <div style="font-size: 12px;">
              <span style="color: #64748b;">买家手机: </span>
              <span style="font-weight: 600;">{{ row.buyerContact }}</span>
            </div>
            <div style="font-size: 12px; margin-top: 2px;">
              <span style="color: #64748b;">实际付款: </span>
              <span v-if="row.peerPayerId" style="font-weight: 600; color: #16a34a;">
                {{ row.peerPayerId }}
              </span>
              <span v-else style="color: #94a3b8;">待付款</span>
            </div>
            <div v-if="row.peerMessage" class="peer-msg-preview">
              💬 {{ row.peerMessage }}
            </div>
          </template>
        </el-table-column>

        <!-- 商品与服务类型 -->
        <el-table-column label="商品交付类型" width="130">
          <template #default="{ row }">
            <el-tag :type="row.goodsType === 2 ? 'warning' : 'info'" size="small">
              {{ row.goodsType === 2 ? '⚡ 线上服务/卡密' : '📦 实物物流' }}
            </el-tag>
            <div v-if="row.serviceVoucher" class="voucher-preview" title="交付凭证已录入">
              ✓ 凭据已存档
            </div>
          </template>
        </el-table-column>

        <!-- 金额明细与分成监管 -->
        <el-table-column label="交易金额与分成" width="170">
          <template #default="{ row }">
            <div>成交总额：<span style="font-weight: 700; color: #e11d48;">￥{{ row.payAmount }}</span></div>
            <div style="font-size: 11px; color: #16a34a; margin-top: 2px;">
              平台抽成：+￥{{ row.commissionAmount }} ({{ row.commissionRate }}%)
            </div>
            <div style="font-size: 11px; color: #2563eb;">商户应结：￥{{ row.merchantSettleAmount }}</div>
          </template>
        </el-table-column>

        <!-- 订单状态 -->
        <el-table-column label="订单状态" width="110">
          <template #default="{ row }">
            <el-tag :type="getStatusTag(row.orderStatus)">{{ row.orderStatus }}</el-tag>
          </template>
        </el-table-column>

        <!-- 分账与合规流水 -->
        <el-table-column label="二级分账结算" width="120">
          <template #default="{ row }">
            <el-tag :type="row.sharingStatus === 'FINISHED' ? 'success' : 'warning'">
              {{ row.sharingStatus === 'FINISHED' ? '分账完毕' : '待分账' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '../../api/request'

const loading = ref(false)
const orders = ref([])
const totalOrders = ref(0)
const totalTurnover = ref(0)
const totalCommission = ref(0)
const peerPayCount = ref(0)

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
    const res = await request.get('/admin/orders')
    orders.value = res.data.orders || []
    totalOrders.value = res.data.totalOrders || 0
    totalTurnover.value = res.data.totalTurnover || 0
    totalCommission.value = res.data.totalCommission || 0

    // 计算代付订单数
    peerPayCount.value = orders.value.filter(o => o.peerPayType > 0).length
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

.stats-row {
  margin-bottom: 20px;
}

.stat-card {
  border-radius: 8px;
  border: 1px solid #e2e8f0;
}

.stat-label {
  font-size: 12px;
  color: #64748b;
  font-weight: 500;
}

.stat-val {
  font-size: 22px;
  font-weight: 800;
  color: #0f172a;
  margin-top: 6px;
}

.stat-unit {
  font-size: 13px;
  font-weight: normal;
  color: #94a3b8;
}

.text-rose {
  color: #e11d48;
}

.text-green {
  color: #16a34a;
}

.text-blue {
  color: #2563eb;
}

.table-card {
  border-radius: 8px;
}

.peer-msg-preview {
  font-size: 11px;
  color: #b45309;
  background: #fffbeb;
  padding: 2px 6px;
  border-radius: 4px;
  margin-top: 3px;
  max-width: 190px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.voucher-preview {
  font-size: 10px;
  color: #15803d;
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  padding: 1px 4px;
  border-radius: 3px;
  margin-top: 3px;
  display: inline-block;
}
</style>
