<!--
文件：RefundRequests.vue
用途：F05 商家退款申请审批页：列出本店退款申请，支持同意（执行原路退款）与拒绝。
维护注意：授权由后端 /api/v1/merchant/refund-requests 强制 MERCHANT_ADMIN/SUPERADMIN；超管可看全平台，商家仅本店。
-->
<template>
  <div class="refund-page">
    <div class="page-header">
      <h2>退款申请审批</h2>
      <p>买家发起的退款申请在此审批；同意后系统按原路退回付款人，拒绝需填写原因。</p>
    </div>

    <el-card shadow="never" class="filter-card">
      <el-form inline @submit.prevent>
        <el-form-item label="状态">
          <el-select v-model="status" placeholder="全部" clearable style="width: 140px" @change="reload">
            <el-option value="PENDING" label="待处理" />
            <el-option value="APPROVED" label="已同意" />
            <el-option value="REJECTED" label="已拒绝" />
            <el-option value="CANCELLED" label="已撤回" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" @click="reload">查询</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="table-card">
      <el-table :data="rows" stripe style="width: 100%" v-loading="loading">
        <el-table-column prop="id" label="申请ID" width="80" />
        <el-table-column prop="orderSn" label="订单号" min-width="160" show-overflow-tooltip />
        <el-table-column label="退款金额" width="110">
          <template #default="{ row }">￥{{ row.refundAmount }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="退款原因" min-width="160" show-overflow-tooltip />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" effect="light">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="appliedAt" label="申请时间" width="170" />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'PENDING'" type="success" size="small" @click="approve(row)">同意退款</el-button>
            <el-button v-if="row.status === 'PENDING'" type="danger" size="small" @click="openReject(row)">拒绝</el-button>
            <span v-if="row.rejectReason" class="reject-reason" :title="row.rejectReason">{{ row.rejectReason }}</span>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无退款申请" /></template>
      </el-table>
      <div class="pager">
        <el-pagination background layout="total, prev, pager, next" :total="total"
          :current-page="page" :page-size="size" @current-change="p => { page = p; load() }" />
      </div>
    </el-card>

    <el-dialog v-model="rejectDialog" title="拒绝退款申请" width="460px">
      <p class="dialog-tip">订单：<strong>{{ current?.orderSn }}</strong>，退款金额 ￥{{ current?.refundAmount }}</p>
      <el-form label-width="90px">
        <el-form-item label="拒绝原因" required>
          <el-input v-model="rejectReason" type="textarea" :rows="3" maxlength="256" show-word-limit
                    placeholder="请填写拒绝原因，将展示给买家" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectDialog = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="submitReject">确认拒绝</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../../api/request'

const loading = ref(false)
const submitting = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const status = ref('PENDING')

const current = ref(null)
const rejectDialog = ref(false)
const rejectReason = ref('')

const statusLabel = s => ({ PENDING: '待处理', APPROVED: '已同意', REJECTED: '已拒绝', CANCELLED: '已撤回' }[s] || s)
const statusTag = s => ({ PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger', CANCELLED: 'info' }[s] || 'info')

const load = async () => {
  loading.value = true
  try {
    const params = { page: page.value, size: size.value }
    if (status.value) params.status = status.value
    const res = await request.get('/merchant/refund-requests', { params })
    rows.value = res.data.list
    total.value = Number(res.data.total)
  } finally { loading.value = false }
}
const reload = () => { page.value = 1; load() }

const approve = async row => {
  try {
    await request.post(`/merchant/refund-requests/${row.id}/approve`)
    ElMessage.success('已同意退款，原路退回中')
    load()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '同意退款失败')
  }
}

const openReject = row => { current.value = row; rejectReason.value = ''; rejectDialog.value = true }
const submitReject = async () => {
  if (!rejectReason.value.trim()) { ElMessage.warning('请填写拒绝原因'); return }
  submitting.value = true
  try {
    await request.post(`/merchant/refund-requests/${current.value.id}/reject`, { rejectReason: rejectReason.value.trim() })
    ElMessage.success('已拒绝退款申请')
    rejectDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '操作失败')
  } finally { submitting.value = false }
}

onMounted(load)
</script>

<style scoped>
.page-header { margin-bottom: 20px; }
.page-header h2 { margin: 0; font-size: 20px; color: #0f172a; }
.page-header p { margin: 4px 0 0; font-size: 13px; color: #64748b; }
.filter-card { margin-bottom: 16px; border-radius: 8px; }
.table-card { border-radius: 8px; }
.pager { margin-top: 16px; display: flex; justify-content: flex-end; }
.reject-reason { color: #dc2626; font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 200px; display: inline-block; vertical-align: middle; }
.dialog-tip { margin: 0 0 12px; font-size: 13px; color: #475569; }
</style>
