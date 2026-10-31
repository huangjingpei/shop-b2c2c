<template>
  <div class="merchant-cards">
    <div class="page-header">
      <div>
        <h2>16688 虚拟卡密库管理 (自动秒发)</h2>
        <p>支持多行批量粘贴卡密入库，下单自动核销并秒级下发给买家</p>
      </div>
      <el-button type="primary" icon="Upload" @click="dialogVisible = true">批量录入卡密</el-button>
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
            <el-tag :type="row.status === 0 ? 'success' : 'info'">
              {{ row.status === 0 ? '未售出 (在库)' : '已核销售出' }}
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
      </el-table>
    </el-card>

    <!-- 批量导入卡密弹窗 -->
    <el-dialog v-model="dialogVisible" title="批量录入虚拟卡密" width="540px">
      <el-form label-position="top">
        <el-form-item label="所属商品与规格变体 SKU ID" required>
          <el-input v-model="importForm.skuId" placeholder="请输入规格 SKU ID (如 1)" />
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
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="handleImport">立即导入并增加库存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../../api/request'

const loading = ref(false)
const submitLoading = ref(false)
const dialogVisible = ref(false)
const cards = ref([])

const importForm = ref({
  skuId: 1,
  rawText: ''
})

const loadCards = async () => {
  loading.value = true
  try {
    const res = await request.get('/merchant/cards')
    cards.value = res.data
  } finally {
    loading.value = false
  }
}

const handleImport = async () => {
  const lines = importForm.value.rawText.split('\n').map(l => l.trim()).filter(Boolean)
  if (lines.length === 0) {
    ElMessage.warning('请输入至少一行卡密')
    return
  }

  submitLoading.value = true
  try {
    const res = await request.post('/merchant/cards/import', {
      productId: 1, // 关联演示商品
      skuId: importForm.value.skuId,
      cardContents: lines
    })
    ElMessage.success(`成功导入 ${res.data.importedCount} 条卡密，当前可用总库存更新为 ${res.data.newStock}`)
    dialogVisible.value = false
    importForm.value.rawText = ''
    loadCards()
  } finally {
    submitLoading.value = false
  }
}

onMounted(() => {
  loadCards()
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
