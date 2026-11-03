<!--
文件：Merchants.vue
用途：平台商户资料、佣金、服务费、营业状态和收款模式配置页面。
维护注意：进件仍有模拟数据，不能把成功提示当真实渠道开通；后台校验为权威；服务费五种编码分别展示，未知值须明确选择，配置不代表扣费或停服；日期按LocalDate提交。
-->
<template>
  <div class="merchants-page">
    <div class="page-header">
      <div>
        <h2>入驻商户治理与服务费监管</h2>
        <p>个人自然人免照开店、SaaS月度技术服务费阶梯配置与合规结算通道调配</p>
      </div>
      <el-button type="primary" icon="Plus" @click="openDialog">新增/审批开通商户</el-button>
    </div>

    <el-card shadow="never" class="table-card">
      <el-table :data="merchants" stripe style="width: 100%" v-loading="loading">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="storeCode" label="编码" width="100" />
        <el-table-column prop="storeName" label="店铺名称 / 公告" min-width="170">
          <template #default="{ row }">
            <div style="font-weight: 600; color: #1e293b;">{{ row.storeName }}</div>
            <div style="font-size: 11px; color: #64748b;">{{ row.notice || '暂无公告' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="商户性质" width="120">
          <template #default="{ row }">
            <el-tag :type="row.merchantType === 1 ? 'success' : 'primary'" size="small">
              {{ row.merchantType === 1 ? '自然人(免执照)' : '企业/个体' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ownerName" label="经营人/电话" width="140">
          <template #default="{ row }">
            <div style="font-weight: 500;">{{ row.ownerName }}</div>
            <div style="font-size: 11px; color: #64748b;">{{ row.contactPhone }}</div>
          </template>
        </el-table-column>

        <!-- ★ 月度技术服务费 (SaaS合规商业支柱) -->
        <el-table-column label="SaaS月技术服务费" width="160">
          <template #default="{ row }">
            <div>
              <span style="font-weight: 700; color: #0f172a;">￥{{ row.monthlyFee || 0 }}</span>
              <span style="font-size: 11px; color: #64748b;"> / 月</span>
            </div>
            <div style="margin-top: 2px;">
              <el-tag :type="feeStatusView(row.feeStatus).type" size="small" effect="plain">
                {{ feeStatusView(row.feeStatus).label }}
              </el-tag>
            </div>
            <div v-if="row.feeFreeUntil" style="font-size: 10px; color: #94a3b8; margin-top: 2px;">
              免费截止: {{ row.feeFreeUntil }}
            </div>
          </template>
        </el-table-column>

        <!-- ★ 结算收款通道模式 -->
        <el-table-column label="收款结算模式" width="140">
          <template #default="{ row }">
            <el-tag v-if="row.paymentChannelType === 'A'" type="primary" size="small">
              A-电商收付通
            </el-tag>
            <el-tag v-else-if="row.paymentChannelType === 'B'" type="warning" size="small">
              B-商户直收码
            </el-tag>
            <el-tag v-else type="info" size="small">
              C-沙箱测试桩
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="commissionRate" label="抽佣比例" width="90">
          <template #default="{ row }">
            <el-tag type="danger" effect="plain">{{ row.commissionRate }}%</el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="status" label="营业状态" width="95">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
              {{ row.status === 1 ? '正常' : '已封禁' }}
            </el-tag>
          </template>
        </el-table-column>

        <!-- 管理操作 -->
        <el-table-column label="管理操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="openFeeDialog(row)">配置服务费</el-button>
            <el-button size="small" type="warning" link @click="openChannelDialog(row)">切换通道</el-button>
            <el-button size="small" type="info" link @click="editCommission(row)">费率</el-button>
            <el-button size="small" :type="row.status === 1 ? 'danger' : 'success'" link @click="toggleStatus(row)">
              {{ row.status === 1 ? '打烊' : '开业' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 1. 新增商户弹窗 -->
    <el-dialog v-model="dialogVisible" title="新增/开通入驻商户 (个人免执照模式)" width="540px">
      <el-form :model="form" label-width="120px">
        <el-form-item label="店铺唯一编码" required>
          <el-input v-model="form.storeCode" placeholder="如 STORE002" />
        </el-form-item>
        <el-form-item label="店铺名称" required>
          <el-input v-model="form.storeName" placeholder="如 极客数码特惠店" />
        </el-form-item>
        <el-form-item label="商户类型">
          <el-radio-group v-model="form.merchantType">
            <el-radio :label="1">个人自然人 (小微免执照)</el-radio>
            <el-radio :label="2">个体工商户/企业</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="经营者姓名" required>
          <el-input v-model="form.ownerName" placeholder="必须与银行卡户名一致" />
        </el-form-item>
        <el-form-item label="身份证号" required>
          <el-input v-model="form.idCardNumber" placeholder="18位居民身份证号码" />
        </el-form-item>
        <el-form-item label="联系手机号" required>
          <el-input v-model="form.contactPhone" placeholder="商户通知手机" />
        </el-form-item>
        <el-divider content-position="left">二级分账银行结算卡</el-divider>
        <el-form-item label="开户银行">
          <el-input v-model="form.bankName" placeholder="如 中国工商银行北京分行" />
        </el-form-item>
        <el-form-item label="银行借记卡号">
          <el-input v-model="form.bankAccountNumber" placeholder="6222 开头个人一类银行卡号" />
        </el-form-item>
        <el-divider content-position="left">工作台管理账号与费率</el-divider>
        <el-form-item label="店长登录账号" required>
          <el-input v-model="form.adminUsername" placeholder="如 seller02" />
        </el-form-item>
        <el-form-item label="初始密码" required>
          <el-input v-model="form.adminPassword" placeholder="默认 admin123" />
        </el-form-item>
        <el-form-item label="技术抽成比例(%)">
          <el-input-number v-model="form.commissionRate" :precision="2" :step="0.5" :min="0" :max="100" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="handleCreate">确认开通并进件</el-button>
      </template>
    </el-dialog>

    <!-- 2. ★ 配置商户技术服务费弹窗 -->
    <el-dialog v-model="feeDialogVisible" title="配置商户月度技术服务费 (SaaS合规模式)" width="480px">
      <el-form label-width="120px">
        <el-form-item label="月技术服务费">
          <el-input-number v-model="feeForm.monthlyFee" :precision="2" :step="10" :min="0" />
          <span style="margin-left: 10px; font-size: 12px; color: #64748b;">元 / 月</span>
        </el-form-item>
        <el-form-item label="免费免收截止日">
          <el-date-picker 
            v-model="feeForm.feeFreeUntil" 
            type="date" 
            placeholder="选择免收截止日期" 
            value-format="YYYY-MM-DD"
            style="width: 100%;" 
          />
        </el-form-item>
        <el-form-item label="服务费状态">
          <el-select v-model="feeForm.feeStatus" placeholder="请选择有效状态" style="width: 100%;">
            <el-option v-for="option in FEE_STATUS_OPTIONS" :key="option.value"
              :label="`${option.value} (${option.label})`" :value="option.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <p class="text-xs text-slate-500">这里只保存配置标记，不代表已扣费、豁免执行或自动停服；截止日留空会保留原值。</p>
      <template #footer>
        <el-button @click="feeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="feeSaving" @click="handleSaveFee">保存服务费配置</el-button>
      </template>
    </el-dialog>

    <!-- 3. ★ 切换商户支付结算模式弹窗 -->
    <el-dialog v-model="channelDialogVisible" title="切换收款通道模式 (收付通 / 独立直收 / 测试沙箱)" width="500px">
      <el-form label-width="110px">
        <el-form-item label="结算通道类型">
          <el-radio-group v-model="channelForm.paymentChannelType">
            <el-radio-button label="C">方案C (沙箱测试桩)</el-radio-button>
            <el-radio-button label="B">方案B (商户直收码)</el-radio-button>
            <el-radio-button label="A">方案A (收付通合规分账)</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="channelForm.paymentChannelType === 'B'" label="商户收款二维码">
          <el-input v-model="channelForm.customPayQrcode" placeholder="请输入商户微信/支付宝个人收款码图片URL" />
          <div style="font-size: 11px; color: #e11d48; margin-top: 4px;">
            * 方案B模式下，付款人调起收银台将直接展示此收款码，资金直达商户，平台零二清接触。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="channelDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="channelSaving" @click="handleSaveChannel">保存收款通道</el-button>
      </template>
    </el-dialog>

  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../../api/request'
import { FEE_STATUS_OPTIONS, feeStatusView, serviceFeeForm, serviceFeePayload } from '../../utils/serviceFee.mjs'

const loading = ref(false)
const submitLoading = ref(false)
const merchants = ref([])
const dialogVisible = ref(false)

const form = ref({
  storeCode: '',
  storeName: '',
  merchantType: 1,
  ownerName: '',
  idCardNumber: '',
  contactPhone: '',
  bankAccountNumber: '',
  bankName: '中国工商银行',
  adminUsername: '',
  adminPassword: 'admin123',
  commissionRate: 3.00
})

// 服务费配置
const feeDialogVisible = ref(false)
const feeSaving = ref(false)
const currentStoreId = ref(null)
const feeForm = ref({
  monthlyFee: 0,
  feeFreeUntil: '',
  feeStatus: 'NORMAL'
})

// 通道模式配置
const channelDialogVisible = ref(false)
const channelSaving = ref(false)
const channelForm = ref({
  paymentChannelType: 'C',
  customPayQrcode: ''
})

const loadMerchants = async () => {
  loading.value = true
  try {
    const res = await request.get('/admin/merchants')
    merchants.value = res.data
  } finally {
    loading.value = false
  }
}

const openDialog = () => {
  form.value.storeCode = 'STORE' + Math.floor(100 + Math.random() * 900)
  dialogVisible.value = true
}

const handleCreate = async () => {
  if (!form.value.storeCode || !form.value.storeName || !form.value.ownerName || !form.value.adminUsername) {
    ElMessage.warning('请填写完整的商户与进件必填项')
    return
  }
  submitLoading.value = true
  try {
    await request.post('/admin/merchants', form.value)
    ElMessage.success('商户已开通，二级商户号已分配')
    dialogVisible.value = false
    loadMerchants()
  } finally {
    submitLoading.value = false
  }
}

const toggleStatus = async (row) => {
  const newStatus = row.status === 1 ? 2 : 1
  await request.put(`/admin/merchants/${row.id}/status?status=${newStatus}`)
  ElMessage.success('店铺状态已切换')
  loadMerchants()
}

const editCommission = (row) => {
  ElMessageBox.prompt('请输入新的抽佣比例(百分比，如 3.5 代表 3.5%)', '调整技术服务费提成', {
    inputValue: row.commissionRate,
    inputPattern: /^\d+(\.\d{1,2})?$/,
    inputErrorMessage: '请输入有效的数字'
  }).then(async ({ value }) => {
    await request.put(`/admin/merchants/${row.id}/commission?rate=${value}`)
    ElMessage.success('费率调整成功')
    loadMerchants()
  })
}

// 打开服务费弹窗
const openFeeDialog = (row) => {
  currentStoreId.value = row.id
  feeForm.value = serviceFeeForm(row)
  feeDialogVisible.value = true
}

const handleSaveFee = async () => {
  feeSaving.value = true
  try {
    await request.put(`/admin/merchants/${currentStoreId.value}/service-fee`, serviceFeePayload(feeForm.value))
    ElMessage.success('商户技术服务费配置已保存！')
    feeDialogVisible.value = false
    loadMerchants()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || e.message || '配置保存失败')
  } finally {
    feeSaving.value = false
  }
}

// 打开通道弹窗
const openChannelDialog = (row) => {
  currentStoreId.value = row.id
  channelForm.value = {
    paymentChannelType: row.paymentChannelType || 'C',
    customPayQrcode: row.customPayQrcode || ''
  }
  channelDialogVisible.value = true
}

const handleSaveChannel = async () => {
  channelSaving.value = true
  try {
    await request.put(`/admin/merchants/${currentStoreId.value}/payment-channel`, channelForm.value)
    ElMessage.success('结算收款通道模式已更新！')
    channelDialogVisible.value = false
    loadMerchants()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '通道更新失败')
  } finally {
    channelSaving.value = false
  }
}

onMounted(() => {
  loadMerchants()
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
</style>
