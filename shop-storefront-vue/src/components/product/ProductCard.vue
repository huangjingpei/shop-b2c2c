<template>
  <div class="bg-white rounded-xl border border-slate-200/90 hover:border-orange-500 hover:shadow-xl transition-all duration-200 flex flex-col overflow-hidden group">
    <!-- 主图与状态徽标 -->
    <div class="relative aspect-square sm:aspect-video bg-slate-100 overflow-hidden cursor-pointer" @click="goDetail">
      <img 
        :src="product.mainImage" 
        :alt="product.title" 
        class="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
        loading="lazy" 
      />

      <!-- 交付类型徽标 -->
      <div class="absolute top-2.5 left-2.5 flex flex-wrap gap-1">
        <span
          v-if="product.goodsType === 2"
          class="px-2 py-0.5 rounded text-[11px] font-bold bg-amber-500 text-white shadow-sm flex items-center space-x-1"
        >
          <span>⚡ 自动秒发卡密</span>
        </span>
        <span
          v-else
          class="px-2 py-0.5 rounded text-[11px] font-bold bg-blue-600 text-white shadow-sm flex items-center space-x-1"
        >
          <span>📦 实物现货顺丰</span>
        </span>

        <span class="px-1.5 py-0.5 rounded text-[10px] font-bold bg-slate-900/70 backdrop-blur-sm text-white">
          {{ product.categoryName || '源头精选' }}
        </span>
      </div>

      <!-- 库存状态标签 -->
      <div class="absolute bottom-2 right-2 px-2 py-0.5 rounded bg-black/60 backdrop-blur-sm text-[10px] text-white">
        现货: {{ product.totalStock }} 件
      </div>
    </div>

    <!-- 商品信息体 -->
    <div class="p-4 flex-1 flex flex-col justify-between">
      <div>
        <router-link :to="`/product/${product.id}`">
          <h3 class="font-bold text-slate-900 text-sm line-clamp-2 hover:text-orange-600 transition leading-snug">
            {{ product.title }}
          </h3>
        </router-link>
        <p class="text-xs text-slate-500 mt-1 line-clamp-1">
          {{ product.subTitle || '源头现货 一件起批 品质保障' }}
        </p>

        <!-- 16688 批发阶梯预览标签 -->
        <div class="mt-2.5 bg-orange-50/70 border border-orange-100/80 rounded-md px-2 py-1 text-[11px] text-orange-800 flex items-center justify-between">
          <span>起批门槛：<strong>1件起</strong></span>
          <span class="text-orange-600 font-bold">量大享阶梯底价 ›</span>
        </div>
      </div>

      <div class="mt-4 pt-3 border-t border-slate-100">
        <!-- 商家店铺直达 -->
        <div class="flex items-center justify-between text-xs text-slate-500 mb-2">
          <div class="flex items-center space-x-1.5">
            <span class="text-sm">🏪</span>
            <span class="font-medium text-slate-800 truncate max-w-[130px]">{{ product.storeName }}</span>
          </div>
          <router-link :to="`/store/${product.merchantId}`" class="text-orange-600 font-semibold hover:underline">
            进店 ›
          </router-link>
        </div>

        <!-- 价格行与购买按钮 -->
        <div class="flex items-baseline justify-between">
          <div>
            <span class="text-xs font-bold text-orange-600">￥</span>
            <span class="text-2xl font-black text-orange-600 tracking-tight">{{ product.minPrice }}</span>
            <span class="text-[11px] text-slate-400 ml-0.5">起批</span>
          </div>
          <router-link
            :to="`/product/${product.id}`"
            class="px-3.5 py-1.5 rounded-lg bg-orange-600 hover:bg-orange-700 text-white text-xs font-bold transition shadow-sm flex items-center space-x-1"
          >
            <span>立即选购</span>
          </router-link>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'

const props = defineProps({
  product: {
    type: Object,
    required: true
  }
})

const router = useRouter()
const goDetail = () => {
  router.push(`/product/${props.product.id}`)
}
</script>
