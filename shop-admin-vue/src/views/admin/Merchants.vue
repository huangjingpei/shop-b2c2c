<template>
  <div class="merchants-page">
    <div class="page-header">
      <div>
        <h2>入驻商户治理与进件审批</h2>
        <p>个人自然人凭身份证免照开店，官方直付通/收付通二级商户合规管理</p>
      </div>
      <el-button type="primary" icon="Plus" @click="openDialog">新增/审批开通商户</el-button>
    </div>

    <el-card shadow="never" class="table-card">
      <el-table :data="merchants" stripe style="width: 100%" v-loading="loading">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="storeCode" label="商户编码" width="120" />
        <el-table-column prop="storeName" label="店铺名称" min-width="160">
          <template #default="{ row }">
            <div style="font-weight: 600; color: #1e293b;">{{ row.storeName }}</div>
            <div style="font-size: 11px; color: #64748b;">{{ row.notice || '暂无公告' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="商户性质" width="130">
          <template #default="{ row }">
            <el-tag :type="row.merchantType === 1 ? 'success' : 'primary'" size="small">
              {{ row.merchantType === 1 ? '自然人(免营业执照)' : '个体/企业' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ownerName" label="经营者/姓名" width="110" />
        <el-table-column prop="idCardNumber" label="身份证号码" width="180">
          <template #default="{ row }">
            <span>{{ maskIdCard(row.idCardNumber) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="contactPhone" label="联系电话" width="130" />
        <el-table-column prop="commissionRate" label="抽佣比例" width="110">
          <template #default="{ row }">
            <el-tag type="danger" effect="plain">{{ row.commissionRate }}%</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'">
              {{ row.status === 1 ? '正常营业' : '打烊/禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="editCommission(row)">调整费率</el-button>
            <el-button size="small" :type="row.status === 1 ? 'danger' : 'success'" link @click="toggleStatus(row)">
              {{ row.status === 1 ? '打烊封禁' : '恢复营业' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新增商户弹窗 -->
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
        <el-form-item label="个人结算储蓄卡">
          <el-input v-model="form.bankAccountNumber" placeholder="二级分账结算银联卡号" />
        </el-form-item>
        <el-form-item label="开户银行名称">
          <el-input v-model="form.bankName" placeholder="例如 中国工商银行" />
        </el-form-item>
        <el-form-item label="初始登录账号" required>
          <el-input v-model="form.adminUsername" placeholder="登录后台所用的手机号或账号" />
        </el-form-item>
        <el-form-item label="平台抽佣比例(%)">
          <el-input-number v-model="form.commissionRate" :min="0" :max="50" :precision="2" :step="0.5" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="handleCreate">确认开通进件</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../../api/request'

const loading = ref(false)
const submitLoading = ref(false)
const dialogVisible = ref(false)
const merchants = ref([])

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

const maskIdCard = (id) => {
  if (!id || id.length < 10) return id
  return id.substring(0, 4) + '**********' + id.substring(id.length - 4)
}

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
