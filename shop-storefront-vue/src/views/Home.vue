<template>
  <div class="space-y-6">
    <!-- 1. 首页黄金首屏 Hero 区 -->
    <HeroSection 
      :categories="categories" 
      :selected-cat="selectedCat" 
      @select-category="selectCategory" 
      @open-merchant="merchantModalVisible = true" 
    />

    <!-- 2. 16688 核心优势金刚区 (4大金牌优势) -->
    <AdvantagesSection />

    <!-- 3. 商品货源分类与筛选控制台 -->
    <div class="bg-white rounded-xl border border-slate-200/90 p-4 shadow-sm flex flex-col md:flex-row md:items-center justify-between gap-3">
      <!-- 品类胶囊筛选 -->
      <div class="flex items-center space-x-2 overflow-x-auto pb-1 md:pb-0">
        <button
          @click="selectCategory(null)"
          class="px-3.5 py-1.5 rounded-lg text-xs font-bold transition shrink-0"
          :class="selectedCat === null ? 'bg-orange-600 text-white shadow-xs' : 'bg-slate-100 text-slate-700 hover:bg-slate-200'"
        >
          全部商品 ({{ allProducts.length }})
        </button>
        <button
          v-for="cat in categories"
          :key="cat.id"
          @click="selectCategory(cat.id)"
          class="px-3.5 py-1.5 rounded-lg text-xs font-bold transition shrink-0"
          :class="selectedCat === cat.id ? 'bg-orange-600 text-white shadow-xs' : 'bg-slate-100 text-slate-700 hover:bg-slate-200'"
        >
          {{ cat.name }}
        </button>
      </div>

      <!-- 实物 / 虚拟 类型二次筛选与统计 -->
      <div class="flex items-center space-x-3 text-xs shrink-0 self-end md:self-auto">
        <div class="flex bg-slate-100 p-0.5 rounded-lg">
          <button 
            @click="filterType = 'all'"
            class="px-2.5 py-1 rounded-md text-[11px] font-bold transition"
            :class="filterType === 'all' ? 'bg-white text-slate-900 shadow-xs' : 'text-slate-500 hover:text-slate-800'"
          >
            全部
          </button>
          <button 
            @click="filterType = 'physical'"
            class="px-2.5 py-1 rounded-md text-[11px] font-bold transition"
            :class="filterType === 'physical' ? 'bg-white text-blue-600 shadow-xs' : 'text-slate-500 hover:text-slate-800'"
          >
            📦 实物现货
          </button>
          <button 
            @click="filterType = 'virtual'"
            class="px-2.5 py-1 rounded-md text-[11px] font-bold transition"
            :class="filterType === 'virtual' ? 'bg-white text-amber-600 shadow-xs' : 'text-slate-500 hover:text-slate-800'"
          >
            ⚡ 虚拟秒发
          </button>
        </div>

        <div class="text-slate-400">
          共 <strong class="text-slate-800">{{ filteredProducts.length }}</strong> 款精选源头货
        </div>
      </div>
    </div>

    <!-- 4. 16688 经典高保真商品瀑布流 -->
    <div v-if="loading" class="text-center py-20 bg-white rounded-xl border border-slate-200/90 text-slate-400 text-sm">
      <div class="inline-block animate-spin text-2xl mb-2">⏳</div>
      <div>正在从源头仓库检索货源数据...</div>
    </div>
    
    <div v-else-if="filteredProducts.length === 0" class="text-center py-20 bg-white rounded-xl border border-slate-200/90 shadow-sm">
      <div class="text-4xl mb-3">📦</div>
      <p class="text-slate-700 text-sm font-bold">该筛选条件下暂无现货商品</p>
      <p class="text-slate-400 text-xs mt-1">商家正在火速上架中，或切换至全部品类浏览</p>
      <button 
        @click="resetFilter" 
        class="mt-4 px-4 py-1.5 rounded-lg bg-orange-600 text-white text-xs font-bold hover:bg-orange-700 transition"
      >
        查看全部货源
      </button>
    </div>

    <div v-else class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-5">
      <ProductCard 
        v-for="p in filteredProducts" 
        :key="p.id" 
        :product="p" 
      />
    </div>

    <!-- 5. 交易全链路 4步极速交易流 -->
    <JourneySection />

    <!-- 6. 买卖家双端赋能与权益 -->
    <BenefitsSection />

    <!-- 7. 商户开店指引弹窗 -->
    <MerchantModal 
      :visible="merchantModalVisible" 
      @close="merchantModalVisible = false" 
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import axios from 'axios'

import HeroSection from '../components/home/HeroSection.vue'
import AdvantagesSection from '../components/home/AdvantagesSection.vue'
import BenefitsSection from '../components/home/BenefitsSection.vue'
import JourneySection from '../components/home/JourneySection.vue'
import ProductCard from '../components/product/ProductCard.vue'
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

const resetFilter = () => {
  selectedCat.value = null
  filterType.value = 'all'
  loadData()
}

onMounted(() => {
  loadData()
})
</script>
