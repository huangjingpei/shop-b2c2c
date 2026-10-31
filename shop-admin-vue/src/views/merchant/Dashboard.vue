<template>
  <div class="merchant-dashboard">
    <div class="store-banner">
      <div class="banner-info">
        <h2>🏪 {{ storeInfo.storeName || '极速数卡旗舰店' }}</h2>
        <p>商户编码：<code>{{ storeInfo.storeCode || 'STORE001' }}</code> · 平台抽成比率：<span class="rate">{{ storeInfo.commissionRate || '3.00' }}%</span></p>
      </div>
      <el-tag type="success" size="large" effect="dark">正常营业中</el-tag>
    </div>

    <el-row :gutter="20" class="stat-row">
      <el-col :span="8">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-title">本店在售商品</div>
          <div class="stat-num">{{ productCount }} 件</div>
          <div class="stat-desc">含多规格变体 SKU 现货</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-title">累计成交订单</div>
          <div class="stat-num">{{ orderCount }} 笔</div>
          <div class="stat-desc">自动秒发卡密与实物发货</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-title">二级分账官方状态</div>
          <div class="stat-num" style="color: #16a34a; font-size: 20px;">✅ 微信直结生效</div>
          <div class="stat-desc">净货款直入个人储蓄卡 (零二清)</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 快捷入口卡片 -->
    <el-card shadow="never" class="quick-entry">
      <template #header>
        <span class="header-bold">常用运营快捷通道</span>
      </template>
      <div class="entry-links">
        <router-link to="/merchant/products" class="link-btn">
          <el-icon><Plus /></el-icon> 发布新商品/变体
        </router-link>
        <router-link to="/merchant/cards" class="link-btn highlight">
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
import { ref, onMounted } from 'vue'
import request from '../../api/request'

const storeInfo = ref({})
const productCount = ref(0)
const orderCount = ref(0)

const loadData = async () => {
  try {
    const res = await request.get('/merchant/profile')
    storeInfo.value = res.data.store || {}

    const pRes = await request.get('/merchant/products')
    productCount.value = pRes.data.length

    const oRes = await request.get('/merchant/orders')
    orderCount.value = oRes.data.length
  } catch (e) {
    console.error(e)
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
