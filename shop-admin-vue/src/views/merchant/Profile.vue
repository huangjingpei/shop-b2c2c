<template>
  <div class="merchant-profile">
    <div class="page-title">
      <h2>店铺资料与个人合规分账设置</h2>
      <p>维护店铺对外展示信息，查验微信收付通/支付宝直付通个人银联储蓄卡二级绑定信息</p>
    </div>

    <el-row :gutter="24">
      <!-- 店铺基本资料 -->
      <el-col :span="13">
        <el-card shadow="never" class="box-card">
          <template #header>
            <span class="card-header">店铺前台展示资料</span>
          </template>

          <el-form :model="store" label-width="110px">
            <el-form-item label="店铺名称">
              <el-input v-model="store.storeName" disabled />
            </el-form-item>
            <el-form-item label="商户唯一编码">
              <el-input v-model="store.storeCode" disabled />
            </el-form-item>
            <el-form-item label="店铺公告">
              <el-input v-model="store.notice" type="textarea" :rows="3" placeholder="在买家端店铺主页顶部滚动展示" />
            </el-form-item>
            <el-form-item label="店铺Logo URL">
              <el-input v-model="store.logoUrl" placeholder="图片直链地址" />
            </el-form-item>
            <el-form-item label="客服联系电话">
              <el-input v-model="store.contactPhone" placeholder="用于买家售后沟通" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="saving" @click="saveProfile">保存修改</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <!-- 合规资金二级分账账户 (零二清) -->
      <el-col :span="11">
        <el-card shadow="never" class="box-card">
          <template #header>
            <span class="card-header">官方二级分账结算账户 (零二清合规)</span>
          </template>

          <div class="account-badge">
            <div class="badge-title">自然人小微进件状态</div>
            <el-tag type="success" size="large" effect="dark">
              {{ paymentAccount.applyStatus === 'PASSED' ? '✅ 官方审核已通过并开通' : '审核中' }}
            </el-tag>
          </div>

          <el-descriptions :column="1" border style="margin-top: 16px;">
            <el-descriptions-item label="渠道通道">
              微信电商收付通 (WeChat Ecommerce Pay)
            </el-descriptions-item>
            <el-descriptions-item label="二级商户号 (sub_mchid)">
              <code>{{ paymentAccount.subMchid || '未分配' }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="结算人姓名">
              {{ paymentAccount.bankAccountName || store.ownerName }}
            </el-descriptions-item>
            <el-descriptions-item label="收款个人储蓄卡">
              {{ maskBankCard(paymentAccount.bankAccountNumber) }}
            </el-descriptions-item>
            <el-descriptions-item label="开户银行">
              {{ paymentAccount.bankName || '中国工商银行' }}
            </el-descriptions-item>
            <el-descriptions-item label="结算清算说明">
              <span style="font-size: 12px; color: #64748b;">
                每笔订单买家付款后资金直入专户，履约完成后 API 自动解冻划拨到您的个人卡中，平台不碰货款。
              </span>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../../api/request'

const saving = ref(false)
const store = ref({})
const paymentAccount = ref({})

const maskBankCard = (num) => {
  if (!num || num.length < 10) return num
  return num.substring(0, 4) + ' **** **** ' + num.substring(num.length - 4)
}

const loadProfile = async () => {
  try {
    const res = await request.get('/merchant/profile')
    store.value = res.data.store || {}
    paymentAccount.value = res.data.paymentAccount || {}
  } catch (e) {
    console.error(e)
  }
}

const saveProfile = async () => {
  saving.value = true
  try {
    await request.put('/merchant/profile', store.value)
    ElMessage.success('店铺展示资料已更新')
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadProfile()
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

.box-card {
  border-radius: 8px;
}

.card-header {
  font-weight: 600;
  font-size: 15px;
}

.account-badge {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: #f8fafc;
  border-radius: 6px;
  border: 1px solid #e2e8f0;
}

.badge-title {
  font-size: 13px;
  color: #475569;
  font-weight: 500;
}
</style>
