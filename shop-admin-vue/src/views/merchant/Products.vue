<!--
文件：Products.vue
用途：商家商品与SKU发布及交付类型展示。
维护注意：1实物、2自动卡密、3定制服务；类型2才显示卡密管理。表单便利校验不能替代后端输入/归属校验，新增字段须同步DTO契约。
-->
<template>
  <div class="merchant-products">
    <div class="page-header">
      <div>
        <h2>商品发布与规格变体 SKU 管理</h2>
        <p>挂靠平台标准类目发布实物或虚拟卡密商品，配置多规格阶梯售价</p>
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
            <el-tag :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '在售' : '下架' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <router-link v-if="row.goodsType === 2" to="/merchant/cards">
              <el-button size="small" type="primary" link>卡密库管理</el-button>
            </router-link>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

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
import { ElMessage } from 'element-plus'
import request from '../../api/request'

const loading = ref(false)
const submitLoading = ref(false)
const dialogVisible = ref(false)
const products = ref([])
const categories = ref([])

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
