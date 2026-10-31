<template>
  <div class="space-y-8">
    <!-- 1. 16688 官方 1:1 原生级首屏 (新商入驻 权益加码 + 悬浮动态看板 + 6大权益卡片) -->
    <Official16688Hero />

    <!-- 2. 货源广场 (16688 核心业务商品池) -->
    <section id="services" class="space-y-4 pt-4">
      <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-3 border-b border-slate-200/80 pb-3">
        <div>
          <div class="flex items-center space-x-2">
            <span class="w-2.5 h-6 rounded-full bg-blue-600 inline-block"></span>
            <h2 class="text-xl font-black text-slate-900">货源广场 · 全网精选源头现货</h2>
            <span class="text-xs font-bold text-orange-600 bg-orange-50 px-2 py-0.5 rounded border border-orange-200">
              一件起批 · 密价让利
            </span>
          </div>
          <p class="text-xs text-slate-500 mt-1 pl-4.5">
            数码潮品、服装鞋包、食品日用与数字卡券一站式直采，实物顺丰全国直发，虚拟权益秒提。
          </p>
        </div>

        <!-- 实物 / 虚拟 分类切页 -->
        <div class="flex bg-slate-100 p-1 rounded-xl self-start sm:self-auto shrink-0 text-xs">
          <button 
            @click="filterType = 'all'"
            class="px-3 py-1.5 rounded-lg font-bold transition"
            :class="filterType === 'all' ? 'bg-white text-slate-900 shadow-xs' : 'text-slate-500 hover:text-slate-800'"
          >
            全部货源
          </button>
          <button 
            @click="filterType = 'physical'"
            class="px-3 py-1.5 rounded-lg font-bold transition"
            :class="filterType === 'physical' ? 'bg-white text-blue-600 shadow-xs' : 'text-slate-500 hover:text-slate-800'"
          >
            📦 实物现货
          </button>
          <button 
            @click="filterType = 'virtual'"
            class="px-3 py-1.5 rounded-lg font-bold transition"
            :class="filterType === 'virtual' ? 'bg-white text-amber-600 shadow-xs' : 'text-slate-500 hover:text-slate-800'"
          >
            ⚡ 虚拟秒发
          </button>
        </div>
      </div>

      <!-- 类目二级横向筛选胶囊 -->
      <div class="flex items-center space-x-2 overflow-x-auto pb-1">
        <button
          @click="selectCategory(null)"
          class="px-4 py-1.5 rounded-full text-xs font-bold transition shrink-0"
          :class="selectedCat === null ? 'bg-blue-600 text-white shadow-xs' : 'bg-white border border-slate-200 text-slate-700 hover:bg-slate-50'"
        >
          全部品类 ({{ allProducts.length }})
        </button>
        <button
          v-for="cat in categories"
          :key="cat.id"
          @click="selectCategory(cat.id)"
          class="px-4 py-1.5 rounded-full text-xs font-bold transition shrink-0"
          :class="selectedCat === cat.id ? 'bg-blue-600 text-white shadow-xs' : 'bg-white border border-slate-200 text-slate-700 hover:bg-slate-50'"
        >
          {{ cat.name }}
        </button>
      </div>

      <!-- 商品列表 -->
      <div v-if="loading" class="text-center py-20 bg-white rounded-2xl border border-slate-200 text-slate-400 text-sm">
        <div class="inline-block animate-spin text-2xl mb-2">⏳</div>
        <div>正在从源头仓库检索货源数据...</div>
      </div>

      <div v-else-if="filteredProducts.length === 0" class="text-center py-20 bg-white rounded-2xl border border-slate-200 shadow-sm">
        <div class="text-4xl mb-3">📦</div>
        <p class="text-slate-700 text-sm font-bold">该筛选条件下暂无现货商品</p>
        <p class="text-slate-400 text-xs mt-1">商家正在火速补货中，或切换至其他品类浏览</p>
      </div>

      <div v-else class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-5">
        <ProductCard 
          v-for="p in filteredProducts" 
          :key="p.id" 
          :product="p" 
        />
      </div>
    </section>

    <!-- 3. 交易全链路 4步极速交易流 -->
    <JourneySection />

    <!-- 4. 买卖家双端赋能与权益 -->
    <BenefitsSection />

    <!-- 5. 商户开店指引弹窗 -->
    <MerchantModal 
      :visible="merchantModalVisible" 
      @close="merchantModalVisible = false" 
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import axios from 'axios'

import Official16688Hero from '../components/home/Official16688Hero.vue'
import ProductCard from '../components/product/ProductCard.vue'
import JourneySection from '../components/home/JourneySection.vue'
import BenefitsSection from '../components/home/BenefitsSection.vue'
import MerchantModal from '../components/home/MerchantModal.vue'

const loading = ref(false)
const selectedCat = ref(null)
const filterType = ref('all') // 'all' | 'physical' | 'virtual'
const merchantModalVisible = ref(false)

const categories = ref([])
const allProducts = ref([])

const filteredProducts = computed(() => {
  return allProducts.value.filter(p => {
    if (filterType.value === 'physical') {
      return p.goodsType === 1
    }
    if (filterType.value === 'virtual') {
      return p.goodsType === 2
    }
    return true
  })
})

const loadData = async () => {
  loading.value = true
  try {
    const [cRes, pRes] = await Promise.all([
      axios.get('/api/v1/public/categories'),
      axios.get('/api/v1/public/products', {
        params: { categoryId: selectedCat.value }
      })
    ])
    categories.value = cRes.data.data || []
    allProducts.value = pRes.data.data || []
  } catch (e) {
    console.error('Failed to load home data:', e)
  } finally {
    loading.value = false
  }
}

const selectCategory = (catId) => {
  selectedCat.value = catId
  loadData()
}

onMounted(() => {
  loadData()
})
</script>
