<!--
文件：Complaints.vue
用途：F06 平台超管投诉治理页：全平台工单列表、详情与不可覆盖操作记录、进入仲裁后的裁决（支持/驳回，可联动原路退款）。
维护注意：授权由后端 /api/v1/admin/complaints 强制 SUPERADMIN；裁决金额只做客户端格式预检，可退余额与状态机由后端最终判定。
举证链接只以文本打开新窗口，禁止 v-html 渲染；驳回裁决不得携带退款金额。
-->
<template>
  <div class="complaint-page">
    <div class="page-header">
      <h2>投诉仲裁</h2>
      <p>买家提交投诉 → 商家答复 → 买家申请介入后进入平台仲裁；裁决可联动原路退款，所有操作追加留痕不可覆盖。</p>
    </div>

    <el-card shadow="never" class="filter-card">
      <el-form inline @submit.prevent>
        <el-form-item label="状态">
          <el-select v-model="status" placeholder="全部" clearable style="width: 160px" @change="reload">
            <el-option v-for="s in COMPLAINT_STATUSES" :key="s" :value="s" :label="complaintStatusLabel(s)" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" @click="reload">查询</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="table-card">
      <el-table :data="rows" stripe style="width: 100%" v-loading="loading">
        <el-table-column prop="id" label="工单ID" width="80" />
        <el-table-column prop="orderSn" label="订单号" min-width="160" show-overflow-tooltip />
        <el-table-column label="类型" width="110">
          <template #default="{ row }">{{ complaintTypeLabel(row.complaintType) }}</template>
        </el-table-column>
        <el-table-column prop="content" label="投诉说明" min-width="200" show-overflow-tooltip />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="complaintStatusTag(row.status)" effect="light">{{ complaintStatusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="退款" width="100">
          <template #default="{ row }">￥{{ row.refundAmount }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="提交时间" width="170" />
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openDetail(row)">详情</el-button>
            <el-button v-if="canArbitrateComplaint(row.status)" type="danger" size="small" @click="openArbitrate(row)">裁决</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无投诉工单" /></template>
      </el-table>
      <div class="pager">
        <el-pagination background layout="total, prev, pager, next" :total="total"
          :current-page="page" :page-size="size" @current-change="p => { page = p; load() }" />
      </div>
    </el-card>

    <el-drawer v-model="detailVisible" title="投诉详情与操作记录" size="520px">
      <template v-if="detail">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="订单号">{{ detail.complaint.orderSn }}</el-descriptions-item>
          <el-descriptions-item label="投诉类型">{{ complaintTypeLabel(detail.complaint.complaintType) }}</el-descriptions-item>
          <el-descriptions-item label="投诉说明">{{ detail.complaint.content }}</el-descriptions-item>
          <el-descriptions-item label="商家答复">{{ detail.complaint.merchantReply || '未答复' }}</el-descriptions-item>
          <el-descriptions-item label="仲裁结论">{{ detail.complaint.adminArbitration || '未裁决' }}</el-descriptions-item>
          <el-descriptions-item label="退款判定">
            {{ detail.complaint.refundDecision }} / ￥{{ detail.complaint.refundAmount }}
          </el-descriptions-item>
        </el-descriptions>
        <div class="evidence" v-if="evidenceLinks(detail.complaint.evidenceImages).length">
          <div class="evidence-title">买家举证</div>
          <a v-for="(url, i) in evidenceLinks(detail.complaint.evidenceImages)" :key="i" :href="url"
             target="_blank" rel="noopener noreferrer" class="evidence-link">{{ url }}</a>
        </div>
        <div class="timeline-title">操作记录（只增不改）</div>
        <el-timeline>
          <el-timeline-item v-for="e in detail.events" :key="e.id" :timestamp="e.createdAt" placement="top">
            <div class="event-head">{{ eventLabel(e.eventType) }} · {{ e.actorRole }}</div>
            <div class="event-note">{{ e.note || '—' }}</div>
          </el-timeline-item>
        </el-timeline>
      </template>
    </el-drawer>

    <el-dialog v-model="arbitrateVisible" title="平台裁决" width="520px">
      <p class="dialog-tip">工单：<strong>{{ current?.orderSn }}</strong>，投诉说明：{{ current?.content }}</p>
      <el-form label-width="100px">
        <el-form-item label="裁决结果" required>
          <el-radio-group v-model="form.decision" @change="onDecisionChange">
            <el-radio label="RESOLVE">支持诉求</el-radio>
            <el-radio label="REJECT">驳回诉求</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="退款金额">
          <el-input v-model="form.refundAmount" :disabled="form.decision === 'REJECT'" placeholder="0.00" />
          <div class="hint">支持诉求时可填 0 表示仅支持诉求不退款；不得超过订单可退余额。</div>
        </el-form-item>
        <el-form-item label="仲裁结论" required>
          <el-input v-model="form.arbitration" type="textarea" :rows="4" maxlength="1000" show-word-limit
                    placeholder="请写明事实认定与处理结论，将同步给买卖双方" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="arbitrateVisible = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="submitArbitrate">确认裁决</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../../api/request'
import {
  COMPLAINT_STATUSES, complaintStatusLabel, complaintStatusTag, complaintTypeLabel,
  canArbitrateComplaint, arbitratePayload
} from '@shared/complaint.mjs'

const loading = ref(false)
const submitting = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const status = ref('ARBITRATING')

const detailVisible = ref(false)
const detail = ref(null)
const arbitrateVisible = ref(false)
const current = ref(null)
const form = reactive({ decision: 'RESOLVE', arbitration: '', refundAmount: '0' })

const eventLabel = type => ({
  SUBMITTED: '买家提交投诉', MERCHANT_REPLIED: '商家答复', ESCALATED: '买家申请平台介入',
  RESOLVED: '平台裁决（支持）', REJECTED: '平台裁决（驳回）'
}[type] || type)

const evidenceLinks = raw => {
  try {
    const list = JSON.parse(raw || '[]')
    return Array.isArray(list) ? list.filter(u => typeof u === 'string') : []
  } catch { return [] }
}

const load = async () => {
  loading.value = true
  try {
    const params = { page: page.value, size: size.value }
    if (status.value) params.status = status.value
    const res = await request.get('/admin/complaints', { params })
    rows.value = res.data.list
    total.value = Number(res.data.total)
  } finally { loading.value = false }
}
const reload = () => { page.value = 1; load() }

const openDetail = async row => {
  detailVisible.value = true
  detail.value = null
  try {
    const res = await request.get(`/admin/complaints/${row.id}`)
    detail.value = res.data
  } catch {
    detailVisible.value = false
    ElMessage.error('加载投诉详情失败')
  }
}

const openArbitrate = row => {
  current.value = row
  form.decision = 'RESOLVE'
  form.arbitration = ''
  form.refundAmount = '0'
  arbitrateVisible.value = true
}
const onDecisionChange = value => { if (value === 'REJECT') form.refundAmount = '0' }

const submitArbitrate = async () => {
  let payload
  try {
    payload = arbitratePayload({ decision: form.decision, arbitration: form.arbitration, refundAmount: form.refundAmount })
  } catch (e) { ElMessage.warning(e.message); return }
  submitting.value = true
  try {
    await request.post(`/admin/complaints/${current.value.id}/arbitrate`, payload)
    ElMessage.success('裁决已提交')
    arbitrateVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '裁决失败')
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
.evidence { margin-top: 16px; }
.evidence-title { font-size: 13px; font-weight: 700; color: #0f172a; margin-bottom: 6px; }
.evidence-link { display: block; font-size: 12px; color: #2563eb; word-break: break-all; margin-bottom: 4px; }
.timeline-title { margin: 20px 0 12px; font-size: 13px; font-weight: 700; color: #0f172a; }
.event-head { font-size: 13px; font-weight: 600; color: #334155; }
.event-note { font-size: 12px; color: #64748b; white-space: pre-wrap; }
.dialog-tip { margin: 0 0 12px; font-size: 13px; color: #475569; }
.hint { font-size: 12px; color: #94a3b8; margin-top: 4px; }
</style>
