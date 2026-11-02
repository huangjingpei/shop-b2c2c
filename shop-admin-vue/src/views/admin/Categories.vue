<!--
文件：Categories.vue
用途：平台类目浏览与新增页面。
维护注意：菜单和表单校验不是授权或层级合法性边界；扩展编辑/删除需同步后端引用校验。
-->
<template>
  <div class="categories-page">
    <div class="page-header">
      <div>
        <h2>平台统一商品类目树</h2>
        <p>Marketplace 模式下统一管控全平台行业分类，商家发布商品时挂靠使用</p>
      </div>
      <el-button type="primary" icon="Plus" @click="dialogVisible = true">新增顶级分类</el-button>
    </div>

    <el-card shadow="never" class="table-card">
      <el-table :data="categories" stripe style="width: 100%" v-loading="loading">
        <el-table-column prop="id" label="类目ID" width="100" />
        <el-table-column prop="name" label="类目名称" min-width="200">
          <template #default="{ row }">
            <span style="font-weight: 600; color: #1e293b;">📂 {{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="icon" label="图标标识" width="140" />
        <el-table-column prop="sortOrder" label="排序权重" width="100" />
        <el-table-column prop="createdAt" label="创建时间" />
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" title="新增平台标准类目" width="420px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="类目名称" required>
          <el-input v-model="form.name" placeholder="如 办公软件 / 工具会员" />
        </el-form-item>
        <el-form-item label="排序权重">
          <el-input-number v-model="form.sortOrder" :min="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleCreate">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../../api/request'

const loading = ref(false)
const dialogVisible = ref(false)
const categories = ref([])

const form = ref({
  name: '',
  sortOrder: 10
})

const loadCategories = async () => {
  loading.value = true
  try {
    const res = await request.get('/public/categories')
    categories.value = res.data
  } finally {
    loading.value = false
  }
}

const handleCreate = async () => {
  if (!form.value.name) {
    ElMessage.warning('请输入类目名称')
    return
  }
  await request.post('/admin/categories', form.value)
  ElMessage.success('类目已添加')
  dialogVisible.value = false
  loadCategories()
}

onMounted(() => {
  loadCategories()
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
