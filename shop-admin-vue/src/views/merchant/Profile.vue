<!--
文件：Profile.vue
用途：本店展示资料与渠道账户信息页面。
维护注意：仅能访问会话绑定店铺；银行资料应受权限和脱敏控制。保存成功不代表真实渠道变更，展示字段需与后端白名单一致。
O9：资质有效期卡片展示身份证/营业执照到期状态（规则见 utils/qualificationExpiry.mjs），
    店长可更新为未来日期（PUT /merchant/profile/qualification-expiries）；到期当天即限制发布新商品，逾期30天自动冻结。
M2：收款卡片只展示后端返回的真实进件/结算账户状态；无记录显示“未提交/未配置”，禁止兜底“审核中”或写死银行名。
    收款模式以 effectiveCollectionMode 为准，商家直收与平台代收的清算说明不同，不能写死“平台不碰货款”。
-->
<template>
  <div class="merchant-profile">
    <div class="page-title">
      <h2>店铺资料与收款账户</h2>
      <p>维护店铺对外展示信息，查看当前生效收款模式、渠道进件状态与已登记结算账户</p>
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
            <!-- F07 实物订单固定运费：数字商品不收取，0 表示包邮 -->
            <el-form-item label="实物订单运费">
              <el-input-number v-model="store.shippingFee" :min="0" :max="1000" :precision="2" :step="1" />
              <span style="margin-left: 10px; font-size: 12px; color: #64748b;">
                元/单，0 表示包邮；仅实物订单计入实付，不参与服务费基数
              </span>
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

      <el-col :span="11">
        <el-card v-if="!isManager" shadow="never" class="box-card">
          <template #header>
            <span class="card-header">收款与渠道进件状态</span>
          </template>
          <el-empty :image-size="70" description="收款模式、进件状态与结算账户仅店长可见" />
        </el-card>
        <el-card v-else shadow="never" class="box-card">
          <template #header>
            <span class="card-header">收款与渠道进件状态</span>
          </template>

          <div class="account-badge">
            <div class="badge-title">当前生效收款模式</div>
            <el-tag :type="collectionModeView.type" size="large" effect="dark">
              {{ collectionModeView.label }}
            </el-tag>
          </div>

          <el-alert v-if="collectionModeView.unknown" type="warning" :closable="false" style="margin-top: 12px;"
            title="未获取到当前收款模式，请刷新页面或联系平台运营确认，切勿据此判断资金路径" />

          <div class="account-badge" style="margin-top: 12px;">
            <div class="badge-title">渠道进件状态</div>
            <el-tag :type="paymentAccountView.type" size="large" effect="dark">
              {{ paymentAccountView.label }}
            </el-tag>
          </div>

          <el-descriptions :column="1" border style="margin-top: 16px;">
            <el-descriptions-item label="渠道通道">
              {{ paymentAccount.channel === 'ALIPAY' ? '支付宝' : (paymentAccount.channel === 'WECHAT' ? '微信支付' : '未配置') }}
            </el-descriptions-item>
            <el-descriptions-item label="二级商户号 (sub_mchid)">
              <code>{{ paymentAccount.subMchid || '未进件' }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="结算人姓名">
              {{ paymentAccount.bankAccountName || '未配置' }}
            </el-descriptions-item>
            <el-descriptions-item label="收款个人储蓄卡">
              {{ maskBankCard(paymentAccount.bankAccountNumber) || '未配置' }}
            </el-descriptions-item>
            <el-descriptions-item label="开户银行">
              {{ paymentAccount.bankName || '未配置' }}
            </el-descriptions-item>
            <el-descriptions-item label="结算清算说明">
              <span style="font-size: 12px; color: #64748b;">{{ settlementNote }}</span>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>

        <!-- M2 阶段一结算账户：只展示后端脱敏值与真实核验状态，不提供前端伪造的审核结论 -->
        <el-card v-if="isManager" shadow="never" class="box-card settlement-card">
          <template #header>
            <span class="card-header">已登记结算账户</span>
          </template>
          <el-empty v-if="settlementAccountViews.length === 0" :image-size="60"
            description="尚未登记结算账户，请联系平台运营登记并核验" />
          <el-table v-else :data="settlementAccountViews" size="small">
            <el-table-column prop="bankName" label="开户银行" min-width="110" />
            <el-table-column prop="accountNoMasked" label="账号（脱敏）" min-width="120" />
            <el-table-column label="核验状态" min-width="90">
              <template #default="{ row }">
                <el-tag :type="row.statusType" effect="plain">{{ row.statusLabel }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="默认" width="70">
              <template #default="{ row }">
                <el-tag v-if="row.isDefault" type="success" size="small">默认</el-tag>
                <span v-else>—</span>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../../api/request'
import { paymentAccountView as buildPaymentAccountView, settlementAccountView } from '../../utils/orderStatistics.mjs'
import { qualificationView, validateExpiryUpdate } from '../../utils/qualificationExpiry.mjs'
import { sessionBucket, sessionGet } from '../../utils/authSession.mjs'

const saving = ref(false)
const savingExpiry = ref(false)
const store = ref({})
const paymentAccount = ref({})
const settlementAccounts = ref([])
const effectiveCollectionMode = ref('')
// 会话隔离：读实际 token 所在桶（商家页缺商家桶时回落超管桶）。
const sessionRole = sessionGet(localStorage, 'roleCode', sessionBucket(localStorage, 'MERCHANT'))
const isManager = ref(sessionRole === 'MERCHANT_ADMIN' || sessionRole === 'SUPERADMIN')

// O9 资质有效期更新表单：value-format 已保证 YYYY-MM-DD，校验规则与后端一致（未来日期）。
const expiryForm = reactive({ idCardExpiry: '', licenseExpiry: '' })

const paymentAccountView = computed(() => buildPaymentAccountView(paymentAccount.value))

// M2：仅按后端 effectiveCollectionMode 展示；缺失显示未知，不默认成商家直收。
const collectionModeView = computed(() => {
  const mode = effectiveCollectionMode.value
  if (mode === 'MERCHANT_DIRECT') return { label: '商家直收', type: 'success', unknown: false }
  if (mode === 'PLATFORM_COLLECT') return { label: '平台代收', type: 'warning', unknown: false }
  return { label: '模式未获取', type: 'info', unknown: true }
})

const settlementNote = computed(() => {
  const mode = effectiveCollectionMode.value
  if (mode === 'MERCHANT_DIRECT') {
    return '买家货款直接进入您的渠道账户，平台不接触货款；平台仅按订单收取技术服务费。'
  }
  if (mode === 'PLATFORM_COLLECT') {
    return '买家货款进入平台服务商商户号，由微信/支付宝按分账指令结算到已绑定账户；平台按订单留存服务费，不形成资金池。'
  }
  return '当前收款模式未获取，请勿据此判断资金路径。'
})

const settlementAccountViews = computed(() => settlementAccounts.value.map(account => ({
  ...account,
  ...settlementAccountView(account)
}))

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
    // 旧接口/旧数据可能没有 shippingFee，统一回显为 0（包邮），避免输入框空值。
    if (store.value.shippingFee == null) store.value.shippingFee = 0
    paymentAccount.value = res.data.paymentAccount || {}
    settlementAccounts.value = Array.isArray(res.data.settlementAccounts) ? res.data.settlementAccounts : []
    effectiveCollectionMode.value = res.data.effectiveCollectionMode || ''
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

.settlement-card {
  margin-top: 24px;
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
