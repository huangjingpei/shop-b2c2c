<!--
文件：Cards.vue
用途：本店卡密去重导入、成功批次/成员查询、脱敏库存浏览、店长按SKU限量查看明文、二次确认导出、安全作废及存量一致性核对。
维护注意：列表只展示指纹尾号派生的脱敏标识，不保存也不打印明文；单条查看与导出必须先经确认弹窗，导出结果仅在内存生成下载，不写日志/本地缓存；权限由服务端接口层核验，前端隐藏按钮不是权限边界。批次/成员游标分别管理，不伪造旧批次；未确认作废结果保留原因供重试。一致性核对为只读账实比对，失败保留上次结果，不自动修复漂移。
-->
<template>
  <div class="merchant-cards">
    <div class="page-header">
      <div>
        <h2>16688 虚拟卡密库管理 (自动秒发)</h2>
        <p>按商品与SKU批量入库；重复卡密整批拒绝，到账确认后按订单自动发卡</p>
      </div>
      <div><el-button :disabled="loading" @click="loadCards(false)">刷新列表</el-button><el-button v-if="currentRole === 'MERCHANT_ADMIN' || currentRole === 'SUPERADMIN'" type="primary" icon="Upload" @click="dialogVisible = true">批量录入卡密</el-button></div>
    </div>

    <!-- 卡密列表 -->
    <el-card shadow="never" class="table-card">
      <div style="display: flex; gap: 12px; margin-bottom: 12px; flex-wrap: wrap;">
        <el-input v-model="cardSku" :disabled="loading" placeholder="按SKU ID筛选，留空查询本店" style="width: 260px;" />
        <el-checkbox v-model="onlyAvailable" :disabled="loading" @change="loadCards(false)">仅未售</el-checkbox>
        <el-button :disabled="loading" @click="loadCards(false)">查询</el-button>
        <el-button v-if="cardNext" :disabled="loading" @click="loadCards(true)">加载更多</el-button>
      </div>
      <div v-if="canExport" style="display: flex; gap: 12px; margin-bottom: 12px;">
        <el-input v-model="exportSku" :disabled="exportLoading" placeholder="导出SKU ID（明文，最多500条）" style="width: 260px;" />
        <el-button type="warning" :loading="exportLoading" :disabled="exportLoading" @click="handleExport">二次确认后导出明文</el-button>
      </div>
      <el-table :data="cards" stripe style="width: 100%" v-loading="loading">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="skuId" label="关联SKU" width="100" />
        <el-table-column prop="maskedContent" label="卡密内容 (脱敏)" min-width="200">
          <template #default="{ row }">
            <code>{{ row.maskedContent }}</code>
            <el-button v-if="canReveal" link type="primary" :loading="revealLoading === row.id" @click="revealCard(row)">查看</el-button>
          </template>
        </el-table-column>
        <el-table-column prop="cardFingerprint" label="指纹" min-width="160">
          <template #default="{ row }"><code v-if="row.cardFingerprint">{{ row.cardFingerprint }}</code><span v-else style="color:#94a3b8;">--</span></template>
        </el-table-column>
        <el-table-column label="库存售出状态" width="130">
          <template #default="{ row }">
            <el-tag :type="row.status === 0 ? 'success' : row.status === 2 ? 'danger' : 'info'">
              {{ cardStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="orderSn" label="关联售出订单号" width="220">
          <template #default="{ row }">
            <span v-if="row.orderSn">{{ row.orderSn }}</span>
            <span v-else style="color: #94a3b8;">--</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="入库时间" />
        <el-table-column label="操作" width="120"><template #default="{ row }">
          <el-button v-if="canVoidCard(currentRole, row)" type="danger" link :loading="voidLoading === row.id" :disabled="voidLoading !== null" @click="voidCard(row)">作废</el-button>
          <el-button v-if="row.status === 2" link type="primary" @click="showVoidRecord(row)">作废记录</el-button>
        </template></el-table-column>
      </el-table>
    </el-card>

    <!-- F12 存量一致性核对：只读账实比对，异常指纹与漂移提示人工处置，不自动修复 -->
    <el-card shadow="never" style="margin-top: 20px;">
      <h3>库存一致性核对</h3>
      <p>只读比对账面库存与在库未售卡数；预占中数量仅作参考。异常指纹的卡密无法参与导入去重，请按SKU筛选上方列表后走作废流程。核对不会自动修复数据，请勿用导入或作废强行平账。</p>
      <div style="display: flex; gap: 12px; margin-bottom: 12px; align-items: center;">
        <el-button :loading="consistencyLoading" :disabled="consistencyLoading" @click="runConsistency">开始核对</el-button>
        <el-tag v-if="consistencyRan" :type="abnormalCount > 0 ? 'danger' : 'success'">
          {{ abnormalCount > 0 ? `发现 ${abnormalCount} 个异常SKU（含异常指纹 ${fingerprintUnavailableTotal} 张）` : '全部SKU账实一致' }}
        </el-tag>
      </div>
      <el-table v-if="consistencyRan" :data="consistencyItems" v-loading="consistencyLoading" :row-class-name="consistencyRowClass" stripe style="width: 100%">
        <el-table-column prop="skuId" label="SKU" width="90" />
        <el-table-column prop="productId" label="商品ID" width="90" />
        <el-table-column prop="stockQuantity" label="账面库存" width="90" />
        <el-table-column prop="availableCards" label="在库未售卡" width="110" />
        <el-table-column prop="heldQuantity" label="预占中" width="85" />
        <el-table-column prop="soldCards" label="已售" width="80" />
        <el-table-column prop="voidedCards" label="已作废" width="85" />
        <el-table-column prop="fingerprintUnavailable" label="异常指纹" width="90" />
        <el-table-column label="核对结果" min-width="150">
          <template #default="{ row }">
            <el-tag :type="consistencyTagType(row)">{{ consistencyLabel(row) }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <p v-if="consistencyRan && abnormalCount > 0" style="color: #b45309; margin-bottom: 0;">
        异常处置指引：指纹异常的卡密请作废后按需重新导入；账实漂移请先核对导入批次、发卡与作废记录，仍无法定位请联系平台协助，不要用导入或作废强行平账。
      </p>
    </el-card>

    <el-card shadow="never" style="margin-top: 20px;">
      <h3>成功导入批次</h3>
      <p>仅记录本功能启用后的导入。前后库存是当时快照，成员状态按查询时显示。</p>
      <div style="display: flex; gap: 12px; margin-bottom: 12px;">
        <el-input v-model="batchSku" :disabled="batchLoading" placeholder="按SKU ID筛选，留空查询本店" style="width: 300px;" />
        <el-button :disabled="batchLoading" @click="loadBatches(false)">查询/刷新</el-button>
        <el-button v-if="batchNext" :disabled="batchLoading" @click="loadBatches(true)">加载更多</el-button>
      </div>
      <el-table :data="batches" v-loading="batchLoading">
        <el-table-column prop="id" label="批次ID" width="100" />
        <el-table-column prop="productId" label="商品ID" width="90" />
        <el-table-column prop="skuId" label="SKU" width="90" />
        <el-table-column prop="actorId" label="操作人ID" width="100" />
        <el-table-column prop="importedCount" label="导入数" width="85" />
        <el-table-column prop="beforeStock" label="导入前" width="85" />
        <el-table-column prop="afterStock" label="导入后" width="85" />
        <el-table-column prop="createdAt" label="时间" min-width="170" />
        <el-table-column label="成员" width="90"><template #default="{ row }">
          <el-button link type="primary" :disabled="memberLoading" @click="openBatch(row)">明细</el-button>
        </template></el-table-column>
      </el-table>
    </el-card>
    <el-dialog v-model="membersVisible" :title="`批次 ${selectedBatch} 成员`" width="650px">
      <el-table :data="members" v-loading="memberLoading">
        <el-table-column prop="cardId" label="卡ID" />
        <el-table-column label="当前状态"><template #default="{ row }">{{ cardStatusLabel(row.status) }}</template></el-table-column>
        <el-table-column prop="orderSn" label="售出订单号" />
      </el-table>
      <template #footer>
        <el-button v-if="memberNext" :disabled="memberLoading" @click="loadMembers(true)">加载更多</el-button>
        <el-button :disabled="memberLoading" @click="loadMembers(false)">刷新状态</el-button>
        <el-button @click="membersVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 批量导入卡密弹窗 -->
    <el-dialog v-model="dialogVisible" title="批量录入虚拟卡密" width="540px" :close-on-click-modal="false" :close-on-press-escape="!submitLoading" :show-close="!submitLoading">
      <el-alert title="每批最多1000条，重复时整批拒绝；结果未确认时请先核对列表。" type="info" :closable="false" />
      <el-form label-position="top" :disabled="submitLoading">
        <el-form-item label="本店自动卡密商品" required>
          <el-select v-model="importForm.productId" placeholder="请选择商品" @change="loadImportSkus">
            <el-option v-for="p in importProducts" :key="p.id" :label="p.title" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="规格 SKU" required>
          <el-select v-model="importForm.skuId" :loading="skuLoading" placeholder="请选择SKU">
            <el-option v-for="s in importSkus" :key="s.id" :label="s.skuCode" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="卡密数据 (一行一条)" required>
          <el-input
            v-model="importForm.rawText"
            type="textarea"
            :rows="8"
            placeholder="请直接粘贴卡密，每行一条数据，例如：&#10;CARD-2026-AAAA-1111&#10;CARD-2026-BBBB-2222&#10;CARD-2026-CCCC-3333"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="submitLoading" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" :disabled="skuLoading" @click="handleImport">立即导入并增加库存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../../api/request'
import { cardImportPayload, cardStatusLabel, canVoidCard, cardBatchQuery, cardListQuery, cardExportPayload, consistencyIssue } from '../../utils/cardAssets.mjs'
import { merchantProductRows } from '../../utils/merchantProducts.mjs'
import { sessionBucket, sessionGet } from '../../utils/authSession.mjs'

const loading = ref(false)
const submitLoading = ref(false)
const dialogVisible = ref(false)
const cards = ref([])
const cardSku = ref(''), onlyAvailable = ref(false), cardNext = ref(null)
let appliedCardSku = undefined
const revealLoading = ref(null), exportSku = ref(''), exportLoading = ref(false)
const importProducts = ref([]), importSkus = ref([]), skuLoading = ref(false), voidLoading = ref(null)
// 会话隔离：读实际 token 所在桶（商家页缺商家桶时回落超管桶，保住超管代管店铺的展示语义）。
const currentRole = sessionGet(localStorage, 'roleCode', sessionBucket(localStorage, 'MERCHANT')) || ''
const canReveal = currentRole === 'MERCHANT_ADMIN' || currentRole === 'SUPERADMIN'
const canExport = canReveal
const pendingVoidReasons = new Map()
const batches = ref([]), batchSku = ref(''), batchLoading = ref(false), batchNext = ref(null)
const members = ref([]), memberLoading = ref(false), memberNext = ref(null), selectedBatch = ref(null), membersVisible = ref(false)
let appliedBatchSku = undefined

// F12 一致性核对：只读结果不落盘，失败不把旧结果当成一致。
const consistencyItems = ref([]), consistencyLoading = ref(false), consistencyRan = ref(false)
const abnormalCount = ref(0), fingerprintUnavailableTotal = ref(0)
const runConsistency = async () => {
  if (consistencyLoading.value) return
  consistencyLoading.value = true
  try {
    const { data } = await request.get('/merchant/cards/consistency')
    consistencyItems.value = data.items || []
    abnormalCount.value = data.abnormalCount || 0
    fingerprintUnavailableTotal.value = data.fingerprintUnavailableTotal || 0
    consistencyRan.value = true
  } catch { /* 请求层提示，保留上次核对结果不清空。 */ }
  finally { consistencyLoading.value = false }
}
const consistencyLabel = row => ({ ok: '一致', drift: '账实漂移', unavailable: '异常指纹待处置', unknown: '未知' })[consistencyIssue(row)]
const consistencyTagType = row => ({ ok: 'success', drift: 'danger', unavailable: 'warning', unknown: 'info' })[consistencyIssue(row)]
const consistencyRowClass = ({ row }) => consistencyIssue(row) === 'ok' ? '' : 'consistency-abnormal'

const loadBatches = async (more = false) => {
  if (batchLoading.value || (more && !batchNext.value)) return
  let params
  try {
    params = cardBatchQuery(batchSku.value, more ? batchNext.value : null)
    if (more && params.skuId !== appliedBatchSku) { ElMessage.warning('筛选已变化，请先查询/刷新'); return }
  } catch (error) { ElMessage.warning(error.message); return }
  batchLoading.value = true
  try {
    const { data } = await request.get('/merchant/card-import-batches', { params })
    batches.value = more ? [...batches.value, ...data.items] : data.items
    batchNext.value = data.nextBeforeId; appliedBatchSku = params.skuId
  } catch { /* 请求层提示，保留现有记录。 */ }
  finally { batchLoading.value = false }
}
const loadMembers = async (more = false) => {
  if (memberLoading.value || !selectedBatch.value || (more && !memberNext.value)) return
  memberLoading.value = true
  try {
    const { data } = await request.get(`/merchant/card-import-batches/${selectedBatch.value}/cards`, { params: cardBatchQuery(undefined, more ? memberNext.value : null) })
    members.value = more ? [...members.value, ...data.items] : data.items; memberNext.value = data.nextBeforeId
  } catch { /* 不将失败当成空批次，明细不读取卡密正文。 */ }
  finally { memberLoading.value = false }
}
const openBatch = async row => {
  if (memberLoading.value) return
  selectedBatch.value = row.id; members.value = []; memberNext.value = null; membersVisible.value = true
  await loadMembers()
}

const importForm = ref({
  productId: null,
  skuId: null,
  rawText: ''
})

const loadCards = async (more = false) => {
  if (loading.value || (more && !cardNext.value)) return
  let params
  try {
    params = cardListQuery(cardSku.value, onlyAvailable.value, more ? cardNext.value : null)
    if (more && params.skuId !== appliedCardSku) { ElMessage.warning('筛选已变化，请先查询/刷新'); return }
  } catch (error) { ElMessage.warning(error.message); return }
  loading.value = true
  try {
    const { data } = await request.get('/merchant/cards', { params })
    cards.value = more ? [...cards.value, ...data.items] : data.items
    cardNext.value = data.nextBeforeId; appliedCardSku = params.skuId
  } catch { /* 请求层提示，不把错误当成空库存。 */ } finally {
    loading.value = false
  }
}

const revealCard = async row => {
  if (revealLoading.value !== null) return
  try {
    await ElMessageBox.confirm('将向后端申请本条卡密明文，操作会按接口权限校验。确认查看？', '查看卡密明文', {
      confirmButtonText: '确认查看', cancelButtonText: '取消', type: 'warning'
    })
  } catch { return }
  revealLoading.value = row.id
  try {
    const { data } = await request.get(`/merchant/cards/${row.id}`)
    await ElMessageBox.alert(`卡密明文：${data.cardContent}`, '卡密明文（请勿复制到聊天或日志）')
  } catch { /* 失败由请求层提示，不在前端记录明文。 */ } finally {
    revealLoading.value = null
  }
}

const handleExport = async () => {
  if (exportLoading.value) return
  let payload
  try { payload = cardExportPayload(exportSku.value) } catch (error) { ElMessage.warning(error.message); return }
  try {
    await ElMessageBox.confirm(`将导出 SKU ${payload.skuId} 最多500条明文卡密，并记录操作人、数量与时间审计。确认导出？`, '导出明文卡密', {
      confirmButtonText: '确认导出', cancelButtonText: '取消', type: 'warning'
    })
  } catch { return }
  exportLoading.value = true
  try {
    const { data } = await request.post('/merchant/cards/export', payload)
    const text = data.items.map(item => item.cardContent).join('\n')
    const blob = new Blob([text], { type: 'text/plain;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url; link.download = `cards-sku-${data.skuId}.txt`
    link.click(); URL.revokeObjectURL(url)
    ElMessage.success(`已导出 ${data.count} 条，审计已记录`)
  } catch { /* 导出失败不生成文件，错误由请求层提示。 */ } finally {
    exportLoading.value = false
  }
}

const handleImport = async () => {
  if (submitLoading.value || skuLoading.value) return
  let payload
  try { payload = cardImportPayload(importForm.value) } catch (error) { ElMessage.warning(error.message); return }
  submitLoading.value = true
  try {
    const res = await request.post('/merchant/cards/import', payload)
    ElMessage.success(`成功导入 ${res.data.importedCount} 条卡密，批次 ${res.data.batchId ?? '未记录'}，导入后库存 ${res.data.newStock}`)
    dialogVisible.value = false
    importForm.value.rawText = ''
    await loadCards()
    await loadBatches(false)
  } catch { /* 保留本批输入供核对，不记录敏感内容。 */ } finally {
    submitLoading.value = false
  }
}

const loadImportSkus = async () => {
  const id = importForm.value.productId
  importForm.value.skuId = null; importSkus.value = []
  if (!id) return
  skuLoading.value = true
  try {
    const { data } = await request.get(`/merchant/products/${id}/editor`)
    if (importForm.value.productId === id) importSkus.value = data.skus
  } catch { /* 服务端错误由请求层提示。 */ }
  finally { if (importForm.value.productId === id) skuLoading.value = false }
}
const voidCard = async row => {
  if (voidLoading.value !== null) return
  voidLoading.value = row.id
  try {
    let reason
    try {
      const result = await ElMessageBox.prompt('作废后不再发放；有预占或库存不一致时会拒绝。请勿填写卡密或个人信息。', '作废卡密', {
        inputValue: pendingVoidReasons.get(row.id) || '', inputValidator: value => Boolean(value?.trim()) && value.length <= 255 || '请填写最多255字符的原因'
      })
      reason = result.value.trim(); pendingVoidReasons.set(row.id, reason)
    } catch { return }
    await request.post(`/merchant/cards/${row.id}/void`, { reason })
    pendingVoidReasons.delete(row.id); ElMessage.success('卡密已作废'); await loadCards()
  } catch { /* 未确认成功保留原因，下次同卡同原因重试不会再扣数。 */ }
  finally { voidLoading.value = null }
}
const showVoidRecord = async row => {
  try {
    const { data } = await request.get(`/merchant/cards/${row.id}/void-record`)
    await ElMessageBox.alert(`操作人ID：${data.actorId}；库存：${data.beforeStock} → ${data.afterStock}；原因：${data.reason}；时间：${data.createdAt}`, '作废记录')
  } catch { /* 取消弹窗不额外提示，接口失败由请求层处理。 */ }
}

onMounted(() => {
  loadCards()
  loadBatches(false)
  request.get('/merchant/products').then(res => { importProducts.value = merchantProductRows(res.data).filter(p => p.goodsType === 2) }).catch(() => {})
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

code {
  font-family: monospace;
  background: #f1f5f9;
  padding: 2px 6px;
  border-radius: 4px;
  color: #0f172a;
}

:deep(.consistency-abnormal) {
  background: #fffbeb;
}
</style>
