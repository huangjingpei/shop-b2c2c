<!--
文件：Cards.vue
用途：本店卡密去重导入、库存浏览、安全作废及记录查看。
维护注意：选择真实商品/SKU，不绑定演示ID；未确认作废结果时保留原因供同卡重试。作废条件和权限由服务端核验，列表状态含0未售/1售出/2作废，禁止把卡密或请求配置写日志。
-->
<template>
  <div class="merchant-cards">
    <div class="page-header">
      <div>
        <h2>16688 虚拟卡密库管理 (自动秒发)</h2>
        <p>按商品与SKU批量入库；重复卡密整批拒绝，到账确认后按订单自动发卡</p>
      </div>
      <div><el-button :disabled="loading" @click="loadCards">刷新列表</el-button><el-button type="primary" icon="Upload" @click="dialogVisible = true">批量录入卡密</el-button></div>
    </div>

    <!-- 卡密列表 -->
    <el-card shadow="never" class="table-card">
      <el-table :data="cards" stripe style="width: 100%" v-loading="loading">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="skuId" label="关联SKU" width="100" />
        <el-table-column prop="cardContent" label="卡密内容 (激活码/账号密码/提货链接)" min-width="260">
          <template #default="{ row }">
            <code>{{ row.cardContent }}</code>
          </template>
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
import { cardImportPayload, cardStatusLabel, canVoidCard } from '../../utils/cardAssets.mjs'

const loading = ref(false)
const submitLoading = ref(false)
const dialogVisible = ref(false)
const cards = ref([])
const importProducts = ref([]), importSkus = ref([]), skuLoading = ref(false), voidLoading = ref(null)
const currentRole = localStorage.getItem('roleCode') || ''
const pendingVoidReasons = new Map()

const importForm = ref({
  productId: null,
  skuId: null,
  rawText: ''
})

const loadCards = async () => {
  loading.value = true
  try {
    const res = await request.get('/merchant/cards')
    cards.value = res.data
  } catch { /* 请求层提示，不把错误当成空库存。 */ } finally {
    loading.value = false
  }
}

const handleImport = async () => {
  if (submitLoading.value || skuLoading.value) return
  let payload
  try { payload = cardImportPayload(importForm.value) } catch (error) { ElMessage.warning(error.message); return }
  submitLoading.value = true
  try {
    const res = await request.post('/merchant/cards/import', payload)
    ElMessage.success(`成功导入 ${res.data.importedCount} 条卡密，当前可用总库存更新为 ${res.data.newStock}`)
    dialogVisible.value = false
    importForm.value.rawText = ''
    await loadCards()
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
  request.get('/merchant/products').then(res => { importProducts.value = res.data.filter(p => p.goodsType === 2) }).catch(() => {})
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
</style>
