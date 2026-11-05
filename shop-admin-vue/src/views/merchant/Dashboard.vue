<!--
文件：Dashboard.vue
用途：本店实际营业状态、已上架商品、统一订单状态统计及快捷入口。
维护注意：零费率必须保留；统计走专用本店接口，不用订单列表长度算成交。金额按状态而非渠道账本，不展示写死的开通/到账状态；网络缺失显示未知，不能回退演示店铺。
-->
<template>
  <div class="merchant-dashboard">
    <div class="store-banner">
      <div class="banner-info">
        <h2>🏪 {{ banner.name }}</h2>
        <p>商户编码：<code>{{ banner.code }}</code> · 平台抽成比率：<span class="rate">{{ banner.rate }}%</span></p>
      </div>
      <el-tag :type="storeInfo.status === 1 ? 'success' : 'info'" size="large" effect="dark">{{ banner.status }}</el-tag>
    </div>

    <el-row :gutter="20" class="stat-row">
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-title">本店已上架商品</div>
          <div class="stat-num">{{ productCount }} 件</div>
          <div class="stat-desc">按商品上架状态，不代表均有现货</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-title">历史已支付订单数</div>
          <div class="stat-num">{{ stats.paidOrders }} 笔</div>
          <div class="stat-desc">含退款状态，不含待付/关闭订单</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-title">历史已支付订单金额</div>
          <div class="stat-num">￥{{ stats.paidTurnover }}</div>
          <div class="stat-desc">含退款状态金额，非退款净额</div>
        </el-card>
      </el-col>
      <el-col :span="6"><el-card shadow="hover" class="stat-card">
        <div class="stat-title">未退款订单预计佣金</div><div class="stat-num">￥{{ stats.estimatedCommission }}</div>
        <div class="stat-desc">按订单快照，不是已结算金额</div>
      </el-card></el-col>
    </el-row>
    <el-alert :title="STATISTICS_NOTICE" type="info" :closable="false" style="margin-bottom: 16px;" />
    <p>待付款 {{ stats.pendingOrders }} 笔，意向金额 ￥{{ stats.pendingIntentAmount }}；退款状态 {{ stats.refundedOrders }} 笔。</p>
    <el-alert v-if="stats.unknownOrders > 0 || stats.amountAnomalyOrders > 0" :title="`需核对：未知状态 ${stats.unknownOrders} 单，异常金额 ${stats.amountAnomalyOrders} 单。`" type="warning" :closable="false" />

    <!-- 快捷入口卡片 -->
    <el-card shadow="never" class="quick-entry">
      <template #header>
        <span class="header-bold">常用运营快捷通道</span>
      </template>
      <div class="entry-links">
        <router-link to="/merchant/products" class="link-btn">
          <el-icon><Plus /></el-icon> 发布新商品/变体
        </router-link>
        <router-link v-if="canManageCards" to="/merchant/cards" class="link-btn highlight">
          <el-icon><CreditCard /></el-icon> 批量导入虚拟卡密 (16688秒发)
        </router-link>
        <router-link to="/merchant/orders" class="link-btn">
          <el-icon><List /></el-icon> 处理待发货订单
        </router-link>
        <router-link to="/merchant/profile" class="link-btn">
          <el-icon><Shop /></el-icon> 查看个人分账储蓄卡绑定
        </router-link>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import request from '../../api/request'
import { statisticsView, listedProductCount, storeDisplay, STATISTICS_NOTICE } from '../../utils/orderStatistics.mjs'
import { merchantProductRows } from '../../utils/merchantProducts.mjs'

const storeInfo = ref({})
const productCount = ref('—')
const stats = ref(statisticsView())
const banner = computed(() => storeDisplay(storeInfo.value))
// Q13-A：前端仅做入口隐藏，最终以路由守卫和后端接口拒绝为准。
const canManageCards = computed(() => localStorage.getItem('roleCode') !== 'MERCHANT_STAFF')

const loadData = async () => {
  try {
    const res = await request.get('/merchant/profile')
    storeInfo.value = res.data.store || {}

    const pRes = await request.get('/merchant/products')
    productCount.value = listedProductCount(merchantProductRows(pRes.data))

    const oRes = await request.get('/merchant/orders/statistics')
    stats.value = statisticsView(oRes.data)
  } catch {
    // 请求层提示错误，不将失败或旧演示值当作经营数据。
  }
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.store-banner {
  background: #ffffff;
  padding: 24px;
  border-radius: 8px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
  border: 1px solid #e2e8f0;
}

.banner-info h2 {
  margin: 0 0 6px;
  font-size: 22px;
  color: #0f172a;
}

.banner-info p {
  margin: 0;
  font-size: 13px;
  color: #64748b;
}

.rate {
  color: #ef4444;
  font-weight: 700;
}

.stat-row {
  margin-bottom: 24px;
}

.stat-card {
  border-radius: 8px;
  border: none;
}

.stat-title {
  font-size: 13px;
  color: #64748b;
}

.stat-num {
  font-size: 26px;
  font-weight: 700;
  color: #0f172a;
  margin: 8px 0 4px;
}

.stat-desc {
  font-size: 11px;
  color: #94a3b8;
}

.quick-entry {
  border-radius: 8px;
}

.header-bold {
  font-weight: 600;
  font-size: 15px;
}

.entry-links {
  display: flex;
  gap: 16px;
}

.link-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 20px;
  background: #f1f5f9;
  border-radius: 8px;
  color: #334155;
  text-decoration: none;
  font-size: 14px;
  font-weight: 500;
  transition: all 0.2s;
}

.link-btn:hover {
  background: #e2e8f0;
}

.link-btn.highlight {
  background: #eff6ff;
  color: #2563eb;
}
</style>
