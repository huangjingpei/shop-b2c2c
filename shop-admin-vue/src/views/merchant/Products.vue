<!--
文件：Products.vue
用途：商家商品发布、资料/SKU售价编辑、上下架及独立库存调整记录入口。
维护注意：资料保存不回写库存；实物/服务库存由店长或超管单独调整并留痕，自动卡密禁止手调。网络失败保留幂等键，刷新只更新库存不覆盖未保存资料；违规下架不可自行恢复，前端权限不能替代服务端。
-->
<template>
  <div class="merchant-products">
    <div class="page-header">
      <div>
        <h2>商品发布与规格变体 SKU 管理</h2>
        <p>发布实物、自动卡密或定制服务，维护商品资料与SKU售价</p>
      </div>
      <el-button type="primary" icon="Plus" @click="openDialog">发布新商品</el-button>
    </div>

    <el-card shadow="never" class="table-card">
      <el-table :data="products" stripe style="width: 100%" v-loading="loading">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="商品主图" width="100">
          <template #default="{ row }">
            <el-image :src="row.mainImage" style="width: 60px; height: 60px; border-radius: 6px;" fit="cover" />
          </template>
        </el-table-column>
        <el-table-column prop="title" label="商品标题" min-width="200">
          <template #default="{ row }">
            <div style="font-weight: 600; color: #1e293b;">{{ row.title }}</div>
            <div style="font-size: 11px; color: #64748b;">{{ row.subTitle }}</div>
          </template>
        </el-table-column>
        <el-table-column label="交付类型" width="130">
          <template #default="{ row }">
            <el-tag :type="row.goodsType === 2 ? 'warning' : 'info'">
              {{ goodsTypeLabel(row.goodsType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="minPrice" label="展示售价" width="120">
          <template #default="{ row }">
            <span style="font-weight: 600; color: #ef4444;">￥{{ row.minPrice }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="在售状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : row.status === 2 ? 'danger' : 'info'">
              {{ productStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" :disabled="editorLoading || editSaving" @click="openEditor(row)">编辑</el-button>
            <el-button v-if="statusAction(row)" size="small" link :type="row.status === 1 ? 'warning' : 'success'"
              :loading="statusLoading === row.id" :disabled="statusLoading !== null || editSaving" @click="changeStatus(row)">{{ statusAction(row).label }}</el-button>
            <router-link v-if="row.goodsType === 2" to="/merchant/cards">
              <el-button size="small" type="primary" link>卡密库管理</el-button>
            </router-link>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="editVisible" title="编辑商品资料与售价" width="760px" :close-on-click-modal="false" :close-on-press-escape="!editSaving" :show-close="!editSaving">
      <el-alert title="交付类型与SKU编码固定；库存仅供查看，请通过对应库存业务维护。" type="info" :closable="false" />
      <el-form v-if="editForm" :model="editForm" :disabled="editSaving || editorLoading" label-width="110px" style="margin-top: 16px;">
        <el-form-item label="平台类目" required>
          <el-select v-model="editForm.categoryId"><el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" /></el-select>
        </el-form-item>
        <el-form-item label="商品标题" required><el-input v-model="editForm.title" maxlength="255" /></el-form-item>
        <el-form-item label="副标题"><el-input v-model="editForm.subTitle" maxlength="255" /></el-form-item>
        <el-form-item label="主图URL" required><el-input v-model="editForm.mainImage" maxlength="255" /></el-form-item>
        <el-form-item v-if="editingType === 3" label="服务交付说明"><el-input v-model="editForm.serviceDesc" type="textarea" :rows="4" maxlength="10000" /></el-form-item>
        <el-table :data="editForm.skus">
          <el-table-column prop="skuCode" label="SKU编码" />
          <el-table-column prop="specData" label="规格" />
          <el-table-column label="售价(元)" width="190"><template #default="{ row }">
            <el-input-number v-model="row.price" :min="0.01" :max="99999999.99" :precision="2" style="width: 170px;" />
          </template></el-table-column>
          <el-table-column prop="stockQuantity" label="可售库存" width="90" />
          <el-table-column label="库存记录" width="100"><template #default="{ row }">
            <el-button v-if="editingType !== 2" link type="primary" @click="openStock(row)">查看/调整</el-button>
            <span v-else>卡密业务</span>
          </template></el-table-column>
        </el-table>
      </el-form>
      <template #footer>
        <el-button :disabled="editSaving || editorLoading" @click="reloadEditor">重新载入</el-button>
        <el-button :disabled="editSaving" @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" :disabled="editorLoading" @click="saveEditor">保存资料与售价</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="stockVisible" title="库存调整与记录" width="850px" append-to-body :close-on-click-modal="false"
      :close-on-press-escape="!stockSaving" :show-close="!stockSaving">
      <el-alert title="仅调整可售数量，不改变已预占数量。结果未确认时请先核对记录，避免刷新后重复调整。" type="info" :closable="false" />
      <p>SKU：{{ stockSkuCode }}　当前可售：{{ stockForm.expectedStock }}</p>
      <el-form v-if="canAdjustInventory(currentRole, editingType)" label-width="110px" :disabled="stockSaving || stockLoading">
        <el-form-item label="调整数量"><el-input-number v-model="stockForm.delta" :min="-1000000" :max="1000000" :precision="0" /><span style="margin-left: 12px">正数补货，负数扣减</span></el-form-item>
        <el-form-item label="调整原因"><el-input v-model="stockForm.reason" maxlength="255" placeholder="请勿填写卡密或个人信息" /></el-form-item>
        <el-form-item><el-button type="primary" :loading="stockSaving" @click="submitStock">确认调整并记录</el-button></el-form-item>
      </el-form>
      <el-table :data="stockRecords" v-loading="stockLoading">
        <el-table-column prop="createdAt" label="时间" min-width="170" />
        <el-table-column prop="actorId" label="操作人ID" width="95" />
        <el-table-column prop="beforeStock" label="调整前" width="85" />
        <el-table-column prop="delta" label="增减量" width="85" />
        <el-table-column prop="afterStock" label="调整后" width="85" />
        <el-table-column prop="reason" label="原因" min-width="180" />
      </el-table>
      <template #footer>
        <el-button :disabled="stockSaving || stockLoading" @click="refreshStock">核对库存与最新记录</el-button>
        <el-button v-if="stockNext" :disabled="stockSaving || stockLoading" @click="loadStockRecords(true)">加载更多记录</el-button>
        <el-button :disabled="stockSaving" @click="stockVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 发布商品弹窗 -->
    <el-dialog v-model="dialogVisible" title="发布新商品" width="600px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="挂靠平台类目" required>
          <el-select v-model="form.categoryId" placeholder="请选择平台分类">
            <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="商品交付类型" required>
          <el-radio-group v-model="form.goodsType">
            <el-radio :label="2">⚡ 虚拟商品 (卡密自动秒发，16688 模式)</el-radio>
            <el-radio :label="3">定制服务 (人工录入交付凭证)</el-radio>
            <el-radio :label="1">📦 实物商品 (卖家填运单发货)</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="商品标题" required>
          <el-input v-model="form.title" placeholder="如 百度网盘 SVIP 超级会员月卡" />
        </el-form-item>
        <el-form-item label="副标题/卖点">
          <el-input v-model="form.subTitle" placeholder="如 官方直充，24小时秒级自动发货" />
        </el-form-item>
        <el-form-item label="主图图片 URL" required>
          <el-input v-model="form.mainImage" placeholder="https://..." />
        </el-form-item>
        <el-form-item label="起步售价(￥)" required>
          <el-input-number v-model="form.minPrice" :min="0.01" :precision="2" :step="1" />
        </el-form-item>

        <div style="font-weight: 600; margin: 16px 0 8px; font-size: 13px;">规格变体 SKU (至少1个)：</div>
        <div v-for="(sku, idx) in form.skus" :key="idx" class="sku-item">
          <el-input v-model="sku.skuCode" placeholder="SKU编码" style="width: 140px;" />
          <el-input v-model="sku.specData" placeholder='规格JSON，如 {"规格":"月卡"}' style="flex: 1;" />
          <el-input-number v-model="sku.price" placeholder="单价" :precision="2" style="width: 120px;" />
          <el-input-number v-model="sku.stockQuantity" placeholder="物理库存" :min="0" style="width: 100px;" />
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="handleCreate">立即上架</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { goodsTypeLabel } from '../../utils/goodsType.mjs'
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../../api/request'
import { editorForm, updatePayload, statusAction, productStatusLabel } from '../../utils/productEditing.mjs'
import { inventoryPayload, canAdjustInventory } from '../../utils/inventoryAdjustment.mjs'
import { createIdempotentSubmission } from '../../utils/idempotency.mjs'

const loading = ref(false)
const submitLoading = ref(false)
const dialogVisible = ref(false)
const products = ref([])
const categories = ref([])
const editVisible = ref(false), editorLoading = ref(false), editSaving = ref(false), statusLoading = ref(null)
const editForm = ref(null), editingId = ref(null), editingType = ref(null)
const currentRole = localStorage.getItem('roleCode') || ''
const stockVisible = ref(false), stockSaving = ref(false), stockLoading = ref(false), stockNext = ref(null)
const stockSkuCode = ref(''), stockRecords = ref([]), stockForm = ref({})
const stockSubmission = createIdempotentSubmission()

const loadStockRecords = async (more = false) => {
  stockLoading.value = true
  try {
    const { data } = await request.get('/merchant/inventory-adjustments', { params: { skuId: stockForm.value.skuId, beforeId: more ? stockNext.value : undefined, limit: 20 } })
    stockRecords.value = more ? [...stockRecords.value, ...data.items] : data.items
    stockNext.value = data.nextBeforeId
  } catch { /* 请求层统一提示，不把失败当成空记录。 */ }
  finally { stockLoading.value = false }
}
const refreshStock = async () => {
  stockLoading.value = true
  try {
    const { data } = await request.get(`/merchant/products/${stockForm.value.productId}/editor`)
    const sku = data.skus.find(s => s.id === stockForm.value.skuId)
    if (!sku) { ElMessage.warning('SKU已变化，请重新打开商品'); return }
    stockForm.value.expectedStock = sku.stockQuantity
    // 仅刷新库存，不用服务端资料覆盖尚未保存的标题和售价。
    for (const row of editForm.value?.skus || []) {
      const latest = data.skus.find(s => s.id === row.id)
      if (latest) row.stockQuantity = latest.stockQuantity
    }
    await loadStockRecords()
  } catch { /* 保留旧快照，提交时仍由服务端核验预期数量。 */ }
  finally { stockLoading.value = false }
}
const openStock = async row => {
  if (stockSaving.value || stockLoading.value) return
  stockForm.value = { productId: editingId.value, skuId: row.id, delta: 0, expectedStock: row.stockQuantity, reason: '' }
  stockSkuCode.value = row.skuCode; stockRecords.value = []; stockNext.value = null; stockVisible.value = true
  await refreshStock()
}
const submitStock = async () => {
  if (stockSaving.value || stockLoading.value) return
  let submission
  try { submission = stockSubmission.prepare(inventoryPayload(stockForm.value)) }
  catch (error) { ElMessage.warning(error.message); return }
  stockSaving.value = true
  try {
    await request.post('/merchant/inventory-adjustments', submission.body, { headers: submission.headers })
    stockSubmission.complete(); ElMessage.success('库存调整已记录')
    stockForm.value.delta = 0; stockForm.value.reason = ''; await refreshStock()
  } catch { /* 未确认成功时保留表单和重试键。 */ }
  finally { stockSaving.value = false }
}

const openEditor = async row => {
  if (editorLoading.value || editSaving.value) return
  editorLoading.value = true
  try {
    const { data } = await request.get(`/merchant/products/${row.id}/editor`)
    editForm.value = editorForm(data); editingId.value = row.id; editingType.value = data.product.goodsType
    editVisible.value = true
  } catch { /* 请求层显示服务端错误，不记录认证配置。 */ }
  finally { editorLoading.value = false }
}
const reloadEditor = async () => {
  try { await ElMessageBox.confirm('重新载入会覆盖当前未保存的修改，是否继续？', '重新载入', { type: 'warning' }) }
  catch { return }
  await openEditor({ id: editingId.value })
}
const saveEditor = async () => {
  if (editSaving.value || editorLoading.value) return
  let payload
  try { payload = updatePayload(editForm.value) } catch (error) { ElMessage.warning(error.message); return }
  editSaving.value = true
  try {
    await request.put(`/merchant/products/${editingId.value}`, payload)
    ElMessage.success('商品资料与售价已保存'); editVisible.value = false; await loadProducts()
  } catch { /* 冲突保留当前表单，用户可明确选择重新载入。 */ }
  finally { editSaving.value = false }
}
const changeStatus = async row => {
  if (statusLoading.value !== null || editSaving.value) return
  statusLoading.value = row.id
  try {
    const { data } = await request.get(`/merchant/products/${row.id}/editor`)
    const action = statusAction(data.product)
    if (!action) { ElMessage.warning('该商品状态需由平台处理'); return }
    try { await ElMessageBox.confirm(`确定${action.label}商品“${data.product.title}”吗？`, '商品状态', { type: 'warning' }) }
    catch { return }
    await request.patch(`/merchant/products/${row.id}/status`, { revision: data.revision, status: action.target })
    ElMessage.success(`商品已${action.label}`); await loadProducts()
  } catch { /* 请求层统一提示，失败后列表保留原状态。 */ }
  finally { statusLoading.value = null }
}

const form = ref({
  categoryId: 1,
  goodsType: 2,
  title: '',
  subTitle: '',
  mainImage: 'https://images.unsplash.com/photo-1574375927938-d5a98e8ffe85?w=500',
  minPrice: 19.90,
  skus: [
    {
      skuCode: 'SKU' + Math.floor(1000 + Math.random() * 9000),
      specData: '{"规格": "标准版", "有效时长": "1个月"}',
      price: 19.90,
      stockQuantity: 10
    }
  ]
})

const loadProducts = async () => {
  loading.value = true
  try {
    const res = await request.get('/merchant/products')
    products.value = res.data
    const cRes = await request.get('/public/categories')
    categories.value = cRes.data
  } finally {
    loading.value = false
  }
}

const openDialog = () => {
  dialogVisible.value = true
}

const handleCreate = async () => {
  if (!form.value.title || !form.value.mainImage) {
    ElMessage.warning('请填写商品标题与主图')
    return
  }
  submitLoading.value = true
  try {
    await request.post('/merchant/products', form.value)
    ElMessage.success('商品发布成功')
    dialogVisible.value = false
    loadProducts()
  } finally {
    submitLoading.value = false
  }
}

onMounted(() => {
  loadProducts()
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

.sku-item {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
</style>
