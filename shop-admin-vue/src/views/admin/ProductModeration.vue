<!--
文件：ProductModeration.vue
用途：F11 平台商品治理页面：全平台商品分页查询、违规下架、审核恢复与处置记录查看。
维护注意：
 1. 页面只装配参数并展示，授权由后端 /api/v1/admin/** 强制 SUPERADMIN；本地筛选条件不是权限边界。
 2. 违规下架允许从 0(已下架)/1(在售) 发起；恢复仅允许 2(违规下架)，恢复目标状态由后端取最近一次处置前状态。
 3. reason 必填 trim 后 1..255，note 可选 ≤255；规则口径与 utils/productModeration.mjs、后端服务一致。
 4. 列表 Long 字段按全局 Jackson 序列化为字符串返回，前端不做数值转换。
-->
<template>
  <div class="moderation-page">
    <div class="page-header">
      <div>
        <h2>商品违规处置与审核</h2>
        <p>平台对违规商品执行下架处置，商家整改后由平台审核恢复；全部处置留痕可查</p>
      </div>
    </div>

    <el-card shadow="never" class="filter-card">
      <el-form inline @submit.prevent>
        <el-form-item label="店铺ID">
          <el-input v-model="filters.merchantId" placeholder="按店铺筛选" clearable style="width: 140px" @change="reload" />
        </el-form-item>
        <el-form-item label="商品状态">
          <el-select v-model="filters.status" placeholder="全部状态" clearable style="width: 150px" @change="reload">
            <el-option :value="0" label="下架" />
            <el-option :value="1" label="在售" />
            <el-option :value="2" label="违规下架" />
          </el-select>
        </el-form-item>
        <el-form-item label="商品标题">
          <el-input v-model="filters.keyword" placeholder="标题关键词" clearable style="width: 200px" @change="reload" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" @click="reload">查询</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="table-card">
      <el-table :data="rows" stripe style="width: 100%" v-loading="loading">
        <el-table-column prop="id" label="商品ID" width="90" />
        <el-table-column prop="title" label="商品标题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="storeName" label="所属店铺" min-width="140" show-overflow-tooltip />
        <el-table-column prop="merchantId" label="店铺ID" width="90" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">{{ productStatusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="违规信息" min-width="220">
          <template #default="{ row }">
            <template v-if="row.status === 2">
              <div class="violation-reason" :title="row.violationReason">{{ row.violationReason || '—' }}</div>
              <div class="violation-time">{{ row.violatedAt || '' }}</div>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canTakedown(row.status)" type="danger" size="small" @click="openTakedown(row)">违规下架</el-button>
            <el-button v-if="canRestore(row.status)" type="success" size="small" @click="openRestore(row)">审核恢复</el-button>
            <el-button size="small" text type="primary" @click="openAudits(row)">处置记录</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无符合条件的商品" /></template>
      </el-table>
      <div class="pager">
        <el-pagination
          background layout="total, prev, pager, next" :total="total"
          :current-page="page" :page-size="size"
          @current-change="p => { page = p; load() }" />
      </div>
    </el-card>

    <!-- 违规下架 -->
    <el-dialog v-model="takedownDialog" title="违规下架商品" width="460px">
      <p class="dialog-tip">商品：<strong>{{ current?.title }}</strong>（ID {{ current?.id }}）</p>
      <p class="dialog-tip">下架后立即从商城隐藏；已付款订单照常履约。商家可继续编辑资料整改，但无法自行上架。</p>
      <el-form label-width="90px">
        <el-form-item label="处置原因" required>
          <el-input v-model="takedownForm.reason" type="textarea" :rows="3" maxlength="255" show-word-limit
                    placeholder="必填，将展示给商家并写入处置记录" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="takedownDialog = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="submitTakedown">确认下架</el-button>
      </template>
    </el-dialog>

    <!-- 审核恢复 -->
    <el-dialog v-model="restoreDialog" title="审核恢复商品" width="460px">
      <p class="dialog-tip">商品：<strong>{{ current?.title }}</strong>（ID {{ current?.id }}）</p>
      <p class="dialog-tip">恢复后商品回到违规处置前的状态（在售/已下架），违规标记清除。</p>
      <el-form label-width="90px">
        <el-form-item label="审核备注">
          <el-input v-model="restoreForm.note" type="textarea" :rows="3" maxlength="255" show-word-limit
                    placeholder="选填，默认：平台审核通过，恢复商品" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="restoreDialog = false">取消</el-button>
        <el-button type="success" :loading="submitting" @click="submitRestore">确认恢复</el-button>
      </template>
    </el-dialog>

    <!-- 处置记录 -->
    <el-drawer v-model="auditDrawer" :title="`处置记录 - ${current?.title ?? ''}`" size="480px">
      <el-skeleton v-if="auditLoading" :rows="4" animated />
      <el-timeline v-else-if="audits.length" style="padding-left: 4px;">
        <el-timeline-item v-for="item in audits" :key="item.id" :timestamp="item.createdAt" placement="top"
                          :type="item.action === 'TAKEDOWN' ? 'danger' : 'success'">
          <div style="font-weight: 600;">{{ moderationActionLabel(item.action) }}（{{ productStatusLabel(item.fromStatus) }} → {{ productStatusLabel(item.toStatus) }}）</div>
          <div class="audit-line">操作人：平台超管 #{{ item.operatorId }}</div>
          <div class="audit-line">说明：{{ item.reason }}</div>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else description="暂无处置记录" />
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../../api/request'
import { productStatusLabel } from '../../utils/productEditing.mjs'
import { canRestore, canTakedown, moderationActionLabel, restorePayload, takedownPayload } from '../../utils/productModeration.mjs'

const loading = ref(false)
const submitting = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const filters = reactive({ merchantId: '', status: null, keyword: '' })

const current = ref(null)
const takedownDialog = ref(false)
const restoreDialog = ref(false)
const takedownForm = reactive({ reason: '' })
const restoreForm = reactive({ note: '' })

const auditDrawer = ref(false)
const auditLoading = ref(false)
const audits = ref([])

const statusTagType = status => ({ 0: 'info', 1: 'success', 2: 'danger' })[status] ?? 'info'

const load = async () => {
  loading.value = true
  try {
    const params = { page: page.value, size: size.value }
    const merchantId = String(filters.merchantId ?? '').trim()
    if (merchantId) params.merchantId = merchantId
    if (filters.status !== null && filters.status !== undefined && filters.status !== '') params.status = filters.status
    const keyword = String(filters.keyword ?? '').trim()
    if (keyword) params.keyword = keyword
    const res = await request.get('/admin/products', { params })
    rows.value = res.data.list
    total.value = Number(res.data.total)
  } finally {
    loading.value = false
  }
}

const reload = () => { page.value = 1; load() }

const openTakedown = row => { current.value = row; takedownForm.reason = ''; takedownDialog.value = true }
const openRestore = row => { current.value = row; restoreForm.note = ''; restoreDialog.value = true }

const submitTakedown = async () => {
  let body
  try { body = takedownPayload(takedownForm) } catch (err) { ElMessage.warning(err.message); return }
  submitting.value = true
  try {
    await request.post(`/admin/products/${current.value.id}/violation-takedown`, body)
    ElMessage.success('商品已违规下架')
    takedownDialog.value = false
    load()
  } finally {
    submitting.value = false
  }
}

const submitRestore = async () => {
  let body
  try { body = restorePayload(restoreForm) } catch (err) { ElMessage.warning(err.message); return }
  submitting.value = true
  try {
    await request.post(`/admin/products/${current.value.id}/restore`, body)
    ElMessage.success('商品已恢复到处置前状态')
    restoreDialog.value = false
    load()
  } finally {
    submitting.value = false
  }
}

const openAudits = async row => {
  current.value = row
  auditDrawer.value = true
  auditLoading.value = true
  try {
    const res = await request.get(`/admin/products/${row.id}/violation-audits`)
    audits.value = res.data
  } finally {
    auditLoading.value = false
  }
}

onMounted(load)
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
.filter-card {
  margin-bottom: 16px;
  border-radius: 8px;
}
.table-card {
  border-radius: 8px;
}
.pager {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
.violation-reason {
  color: #dc2626;
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.violation-time {
  color: #94a3b8;
  font-size: 12px;
}
.muted {
  color: #cbd5e1;
}
.dialog-tip {
  margin: 0 0 12px;
  font-size: 13px;
  color: #475569;
}
.audit-line {
  font-size: 13px;
  color: #475569;
  margin-top: 2px;
}
</style>
