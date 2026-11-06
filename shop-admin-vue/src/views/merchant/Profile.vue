<!--
文件：Profile.vue
用途：本店展示资料与渠道账户信息页面。
维护注意：仅能访问会话绑定店铺；银行资料应受权限和脱敏控制。保存成功不代表真实渠道变更，展示字段需与后端白名单一致。
O9：资质有效期卡片展示身份证/营业执照到期状态（规则见 utils/qualificationExpiry.mjs），
    店长可更新为未来日期（PUT /merchant/profile/qualification-expiries）；到期当天即限制发布新商品，逾期30天自动冻结。
-->
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

          <el-alert v-if="!isManager" title="当前为员工账号（只读权限），仅店长或管理员有权修改店铺资料与收款设置" type="warning" :closable="false" style="margin-bottom: 16px;" />
          <el-form :model="store" :disabled="!isManager" label-width="110px">
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
            <el-form-item v-if="isManager">
              <el-button type="primary" :loading="saving" @click="saveProfile">保存修改</el-button>
            </el-form-item>
          </el-form>
        </el-card>

        <!-- O9 资质有效期：到期提醒与更新入口；到期当天限制上新，逾期30天自动冻结 -->
        <el-card shadow="never" class="box-card qualification-card">
          <template #header>
            <span class="card-header">资质有效期</span>
          </template>

          <el-alert v-if="hasExpired" type="error" :closable="false" class="qualification-alert"
            title="存在已到期资质：已限制发布新商品；逾期超 30 天未更新将自动冻结店铺，请立即更新有效期" />
          <div v-for="item in qualificationViews" :key="item.name" class="qualification-row">
            <div class="qualification-info">
              <span class="qualification-name">{{ item.name }}</span>
              <el-tag :type="item.tagType" effect="plain">{{ item.label }}</el-tag>
              <span class="qualification-text">{{ item.text }}</span>
            </div>
            <el-date-picker v-if="isManager" v-model="expiryForm[item.field]" type="date"
              value-format="YYYY-MM-DD" placeholder="选择新有效期" :disabled-date="disabledPastDate"
              class="qualification-picker" />
          </div>
          <div v-if="isManager" class="qualification-actions">
            <el-button type="primary" :loading="savingExpiry" @click="saveExpiries">更新有效期</el-button>
            <span class="qualification-tip">须为未来日期；只需更新有变化的资质，留空一项保持不变</span>
          </div>
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
            <el-tag :type="paymentAccountView.type" size="large" effect="dark">
              {{ paymentAccountView.label }}
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
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../../api/request'
import { paymentAccountView as buildPaymentAccountView } from '../../utils/orderStatistics.mjs'
import { qualificationView, validateExpiryUpdate } from '../../utils/qualificationExpiry.mjs'

const saving = ref(false)
const savingExpiry = ref(false)
const store = ref({})
const paymentAccount = ref({})
const isManager = ref(localStorage.getItem('roleCode') === 'MERCHANT_ADMIN' || localStorage.getItem('roleCode') === 'SUPERADMIN')

// O9 资质有效期更新表单：value-format 已保证 YYYY-MM-DD，校验规则与后端一致（未来日期）。
const expiryForm = reactive({ idCardExpiry: '', licenseExpiry: '' })

const paymentAccountView = computed(() => buildPaymentAccountView(paymentAccount.value))

const localToday = () => {
  const now = new Date()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}

const qualificationViews = computed(() => [
  { ...qualificationView(store.value.idCardExpiry || '', '身份证', localToday()), field: 'idCardExpiry' },
  { ...qualificationView(store.value.licenseExpiry || '', '营业执照', localToday()), field: 'licenseExpiry' }
])

const hasExpired = computed(() => qualificationViews.value.some(item => item.status === 'EXPIRED'))

const disabledPastDate = (date) => {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return date.getTime() <= today.getTime()
}

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

// O9 更新资质有效期：前端先按同一口径校验，后端仍为最终裁决；成功后重载资料刷新状态。
const saveExpiries = async () => {
  const result = validateExpiryUpdate(expiryForm.idCardExpiry, expiryForm.licenseExpiry, localToday())
  if (!result.valid) {
    ElMessage.error(result.message)
    return
  }
  savingExpiry.value = true
  try {
    await request.put('/merchant/profile/qualification-expiries', {
      idCardExpiry: result.idCardExpiry || null,
      licenseExpiry: result.licenseExpiry || null
    })
    ElMessage.success('资质有效期已更新')
    expiryForm.idCardExpiry = ''
    expiryForm.licenseExpiry = ''
    await loadProfile()
  } finally {
    savingExpiry.value = false
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

/* O9 资质有效期卡片 */
.qualification-card {
  margin-top: 24px;
}

.qualification-alert {
  margin-bottom: 12px;
}

.qualification-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 0;
  border-bottom: 1px dashed #e2e8f0;
  gap: 12px;
  flex-wrap: wrap;
}

.qualification-row:last-of-type {
  border-bottom: none;
}

.qualification-info {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 260px;
}

.qualification-name {
  font-weight: 600;
  color: #0f172a;
}

.qualification-text {
  font-size: 12px;
  color: #64748b;
}

.qualification-picker {
  width: 180px;
}

.qualification-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}

.qualification-tip {
  font-size: 12px;
  color: #94a3b8;
}
</style>
