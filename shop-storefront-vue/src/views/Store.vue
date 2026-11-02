<template>
  <div v-if="loading" class="text-center py-12 text-slate-400 text-sm">
    正在加载店铺资料与货源...
  </div>
  <div v-else>
    <!-- 店铺招牌 Header -->
    <div class="bg-white rounded-xl border border-slate-200 p-6 md:p-8 mb-6 shadow-sm flex flex-col md:flex-row items-start md:items-center justify-between gap-4">
      <div class="flex items-center space-x-4">
        <div class="w-16 h-16 rounded-xl bg-gradient-to-tr from-brand-500 to-amber-500 text-white flex items-center justify-center text-3xl font-bold shadow-inner">
          🏪
        </div>
        <div>
          <div class="flex items-center space-x-2">
            <h1 class="text-xl font-black text-slate-900">{{ store?.storeName || '店铺主页' }}</h1>
            <span class="px-2 py-0.5 rounded text-[11px] font-semibold bg-green-100 text-green-700">官方认证小微商户</span>
          </div>
          <p class="text-xs text-slate-500 mt-1">商户编码：<code>{{ store?.storeCode }}</code> · 客服电话：{{ store?.contactPhone }}</p>
          <div class="mt-2 text-xs text-amber-700 bg-amber-50 px-3 py-1.5 rounded-lg border border-amber-200/60 inline-block">
            📢 店铺公告：{{ store?.notice || '欢迎光临本店，全自动秒级极速发货！' }}
          </div>
        </div>
      </div>

      <router-link to="/" class="text-xs font-semibold text-brand-600 hover:underline">
        ‹ 返回全平台货源大厅
      </router-link>
    </div>

    <!-- 本店商品列表 -->
    <h2 class="text-base font-bold text-slate-900 mb-4">本店在售全部货源 ({{ products.length }})</h2>
    <div v-if="products.length === 0" class="text-center py-16 bg-white rounded-xl border border-slate-200 text-sm text-slate-400">
      该商户暂未上架商品
    </div>
    <div v-else class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-5">
      <div
        v-for="p in products"
        :key="p.id"
        class="bg-white rounded-xl border border-slate-200 overflow-hidden hover:border-brand-300 hover:shadow-md transition flex flex-col justify-between"
      >
        <div class="aspect-video bg-slate-100 overflow-hidden relative">
          <img :src="p.mainImage" :alt="p.title" class="w-full h-full object-cover" />
          <span v-if="p.goodsType === 3" class="text-xs font-bold text-blue-700">定制服务 · 人工交付</span>
            <span
            v-if="p.goodsType === 2"
            class="absolute top-2 left-2 px-2 py-0.5 rounded text-[10px] font-bold bg-amber-500 text-white"
          >
            ⚡ 自动秒发
          </span>
        </div>
        <div class="p-4 flex-1 flex flex-col justify-between">
          <h3 class="font-bold text-xs text-slate-900 line-clamp-2">{{ p.title }}</h3>
          <div class="mt-3 flex items-center justify-between">
            <span class="text-base font-black text-brand-600">￥{{ p.minPrice }}</span>
            <router-link
              :to="`/product/${p.id}`"
              class="px-3 py-1 rounded bg-brand-500 hover:bg-brand-600 text-white text-xs font-semibold"
            >
              选购
            </router-link>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import axios from 'axios'

const route = useRoute()
const loading = ref(false)
const store = ref(null)
const products = ref([])

const loadStore = async () => {
  loading.value = true
  try {
    const res = await axios.get('/api/v1/public/products', {
      params: { merchantId: route.params.id }
    })
    products.value = res.data.data
    if (products.value.length > 0) {
      store.value = {
        storeName: products.value[0].storeName,
        storeCode: products.value[0].storeCode,
        notice: '官方直营，全自动秒级极速发货！24小时在线客服售后无忧。',
        contactPhone: '13800138000'
      }
    }
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadStore()
})
</script>
