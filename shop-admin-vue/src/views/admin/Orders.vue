<!--
文件：Orders.vue
用途：平台订单监察列表和三方交易信息展示。
维护注意：统计取服务端统一状态口径，金额字符串不转Number；佣金是预计值，FINISHED不证明资金到账。角色标签不代替后端权限；列表保留订单快照而非财务流水。
-->
<template>
  <div class="orders-page">
    <div class="page-header">
      <div>
        <h2>全平台交易订单与代付监察大盘</h2>
        <p>查看订单关联角色、金额快照与分账记录状态；真实资金结果待账本接入核对</p>
      </div>
      <el-button type="primary" plain @click="loadOrders">刷新订单流水</el-button>
    </div>

    <!-- 顶部聚合统计指标卡片 -->
    <el-row :gutter="16" class="stats-row">
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">订单总数（含未付款）</div>
          <div class="stat-val">{{ stats.totalOrders }} <span class="stat-unit">笔</span></div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">历史已支付订单金额（含退款状态）</div>
          <div class="stat-val text-rose">￥{{ stats.paidTurnover }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">未退款订单预计佣金</div>
          <div class="stat-val text-green">￥{{ stats.estimatedCommission }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">本页代付订单数</div>
          <div class="stat-val text-blue">{{ peerPayCount }} <span class="stat-unit">笔</span></div>
        </el-card>
      </el-col>
    </el-row>
    <el-alert :title="STATISTICS_NOTICE" type="info" :closable="false" style="margin-bottom: 16px;" />
    <p>待付款 {{ stats.pendingOrders }} 笔，意向金额 ￥{{ stats.pendingIntentAmount }}；退款状态 {{ stats.refundedOrders }} 笔。</p>
    <el-alert v-if="stats.unknownOrders > 0 || stats.amountAnomalyOrders > 0" :title="`需核对：未知状态 ${stats.unknownOrders} 单，异常金额 ${stats.amountAnomalyOrders} 单；异常金额未计入汇总。`" type="warning" :closable="false" />

    <el-card shadow="never" class="table-card">
      <div class="order-filter">
        <el-select v-model="statusFilter" placeholder="按订单状态筛选" style="width: 160px;" @change="reloadOrders">
          <el-option v-for="option in ORDER_STATUS_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <span class="order-total">筛选结果共 {{ total }} 单</span>
      </div>
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

        <!-- 订单关联角色快照：不是不可篡改的渠道对账账本 -->
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
              {{ goodsTypeLabel(row.goodsType) }}
            </el-tag>
            <div v-if="row.serviceVoucher" class="voucher-preview" title="交付凭证已录入">
              ✓ 凭据已存档
            </div>
          </template>
        </el-table-column>

        <!-- 金额明细与分成监管 -->
        <el-table-column label="交易金额与分成" width="170">
          <template #default="{ row }">
            <div>订单金额：<span style="font-weight: 700; color: #e11d48;">￥{{ row.payAmount }}</span></div>
            <div style="font-size: 11px; color: #16a34a; margin-top: 2px;">
              预计抽成：￥{{ row.commissionAmount }} ({{ row.commissionRate }}%)
            </div>
            <div style="font-size: 11px; color: #2563eb;">预计商家净额：￥{{ row.merchantSettleAmount }}</div>
          </template>
        </el-table-column>

        <!-- 订单状态 -->
        <el-table-column label="订单状态" width="110">
          <template #default="{ row }">
            <el-tag :type="getStatusTag(row.orderStatus)">{{ row.orderStatus }}</el-tag>
          </template>
        </el-table-column>

        <!-- 分账与合规流水 -->
        <el-table-column label="分账记录状态" width="160">
          <template #default="{ row }">
            <el-tag :type="row.sharingStatus === 'FINISHED' ? 'success' : 'warning'">
              {{ sharingRecordLabel(row.sharingStatus) }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="hasMore" class="load-more">
        <el-button :loading="loading" @click="loadOrders(true)">加载更多（已显示 {{ orders.length }} / {{ total }}）</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { goodsTypeLabel } from '../../utils/goodsType.mjs'
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../../api/request'
import { statisticsView, STATISTICS_NOTICE, sharingRecordLabel } from '../../utils/orderStatistics.mjs'
import { ORDER_STATUS_OPTIONS, buildOrderQuery, mergeOrderPage } from '../../utils/orderList.mjs'

const loading = ref(false)
const orders = ref([])
const statusFilter = ref('')
const total = ref(0)
const hasMore = ref(false)
const nextCursor = ref('')
const stats = ref(statisticsView())
const peerPayCount = ref(0)
let loadSequence = 0

const getStatusTag = (status) => {
  switch (status) {
    case 'COMPLETED': return 'success'
    case 'PAID': return 'warning'
    case 'SHIPPED': return 'primary'
    default: return 'info'
  }
}

const loadOrders = async (more = false) => {
  if (more && (!hasMore.value || loading.value)) return
  const sequence = ++loadSequence
  loading.value = true
  try {
    const res = await request.get('/admin/orders', {
      params: buildOrderQuery({
        status: statusFilter.value,
        cursor: more ? nextCursor.value : '',
        pageSize: 20
      })
    })
    if (sequence !== loadSequence) return
    const merged = mergeOrderPage({ orders: orders.value }, res.data, { reset: !more })
    orders.value = merged.orders
    total.value = merged.total
    hasMore.value = merged.hasMore
    nextCursor.value = merged.nextCursor
    stats.value = statisticsView(res.data.statistics)
    peerPayCount.value = merged.orders.filter(o => Number(o.peerPayType) > 0).length
  } catch (error) {
    if (sequence === loadSequence) {
      ElMessage.error(error.response?.data?.message || '订单列表加载失败')
    }
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

const reloadOrders = () => {
  hasMore.value = false
  nextCursor.value = ''
  loadOrders(false)
}

onMounted(() => {
  reloadOrders()
})
</script>

<style scoped>
.order-filter {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.order-total {
  font-size: 12px;
  color: #64748b;
}
.load-more {
  text-align: center;
  padding: 16px 0 4px;
}
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
