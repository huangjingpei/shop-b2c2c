<template>
  <section class="grid grid-cols-1 lg:grid-cols-12 gap-5 mb-5">
    <!-- 左侧品类导航 (3 列) -->
    <div class="hidden lg:block lg:col-span-3 bg-white rounded-xl border border-slate-200/90 p-3 shadow-sm">
      <div class="text-xs font-bold text-slate-800 uppercase px-3 py-2 border-b border-slate-100 flex items-center justify-between">
        <span class="flex items-center space-x-1.5">
          <span>☰</span>
          <span>行业主营类目</span>
        </span>
        <span class="text-[10px] text-orange-600 font-semibold bg-orange-50 px-1.5 py-0.5 rounded">全网源头</span>
      </div>
      <div class="divide-y divide-slate-50 mt-1">
        <button
          v-for="cat in categories"
          :key="cat.id"
          @click="$emit('select-category', cat.id)"
          class="w-full text-left px-3 py-2.5 rounded-lg text-xs font-medium transition flex items-center justify-between group"
          :class="selectedCat === cat.id ? 'bg-orange-50 text-orange-700 font-bold' : 'text-slate-700 hover:bg-slate-50'"
        >
          <div class="flex items-center space-x-2">
            <span class="text-base group-hover:scale-110 transition-transform">
              {{ getCatIcon(cat.name) }}
            </span>
            <span>{{ cat.name }}</span>
          </div>
          <span class="text-slate-300 group-hover:text-orange-500 transition-colors">›</span>
        </button>
      </div>
    </div>

    <!-- 中间品牌营销 Hero 横幅 (6 列) -->
    <div class="lg:col-span-6 bg-gradient-to-tr from-slate-900 via-indigo-950 to-slate-900 rounded-xl text-white p-6 sm:p-8 flex flex-col justify-between shadow-sm relative overflow-hidden">
      <div class="relative z-10 max-w-lg">
        <div class="flex items-center space-x-2 mb-3">
          <span class="px-2.5 py-0.5 rounded-full bg-orange-500/20 text-orange-400 text-xs font-bold uppercase tracking-wider border border-orange-500/30">
            全品类源头现货
          </span>
          <span class="text-[11px] text-slate-300">单件起批 · 阶梯让利</span>
        </div>

        <h1 class="text-2xl sm:text-3xl font-black tracking-tight leading-tight">
          源头工厂现货直供<br />
          <span class="text-orange-400">实物批发 & 虚拟数字权益秒发</span>
        </h1>
        <p class="mt-2.5 text-xs text-slate-300 leading-relaxed">
          涵盖数码潮品、服装鞋包、日用百货与数字卡券。小微个人一张身份证免照合规开店，资金直通官方存管专户（零二清）。
        </p>

        <div class="mt-6 flex flex-wrap gap-2.5">
          <button 
            @click="$emit('select-category', null)" 
            class="px-4 py-2 rounded-lg bg-orange-500 hover:bg-orange-600 text-white font-bold text-xs transition shadow-sm flex items-center space-x-1"
          >
            <span>浏览全网精选现货</span>
            <span>↓</span>
          </button>
          <router-link 
            to="/quick-query" 
            class="px-4 py-2 rounded-lg bg-white/10 hover:bg-white/20 text-white font-semibold text-xs backdrop-blur-sm transition border border-white/15"
          >
            免登录提取卡密/查单 ›
          </router-link>
        </div>
      </div>

      <!-- 装饰性光晕背景 -->
      <div class="absolute -right-10 -bottom-10 w-64 h-64 bg-orange-500/15 rounded-full blur-3xl pointer-events-none"></div>
    </div>

    <!-- 右侧商户与买家工作台中枢 (3 列) -->
    <div class="lg:col-span-3 flex flex-col gap-4">
      <div class="bg-white rounded-xl border border-slate-200/90 p-5 shadow-sm flex flex-col justify-between flex-1">
        <div>
          <div class="flex items-center space-x-3 mb-3">
            <div class="w-10 h-10 rounded-full bg-orange-100 flex items-center justify-center text-xl text-orange-600 font-black">
              🛍️
            </div>
            <div>
              <div class="text-xs font-bold text-slate-900">16688 商家与买家中枢</div>
              <div class="text-[11px] text-slate-400">一张身份证免营业执照开店</div>
            </div>
          </div>

          <div class="space-y-2 mt-4">
            <button
              @click="$emit('open-merchant')"
              class="w-full text-center py-2.5 rounded-lg bg-slate-900 hover:bg-slate-800 text-white text-xs font-bold transition shadow-sm flex items-center justify-center space-x-1"
            >
              <span>🏪 免费入驻开店 / 商家工作台</span>
            </button>
            <router-link
              to="/quick-query"
              class="w-full block text-center py-2 rounded-lg bg-orange-50 hover:bg-orange-100 text-orange-700 text-xs font-bold border border-orange-200 transition"
            >
              ⚡ 免登录极速查单
            </router-link>
          </div>
        </div>

        <!-- 3 大合规信赖底线 -->
        <div class="mt-4 pt-3 border-t border-slate-100 text-[11px] text-slate-500 space-y-1.5">
          <div class="flex items-center space-x-1.5 text-slate-700 font-medium">
            <span class="text-emerald-600 font-bold">✓</span>
            <span>微信/支付宝官方二级直结 (零二清)</span>
          </div>
          <div class="flex items-center space-x-1.5 text-slate-700 font-medium">
            <span class="text-emerald-600 font-bold">✓</span>
            <span>实物顺丰全国配 / 虚拟卡密自动秒提</span>
          </div>
          <div class="flex items-center space-x-1.5 text-slate-700 font-medium">
            <span class="text-emerald-600 font-bold">✓</span>
            <span>依据《电商法》第十条个人免照开店</span>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
defineProps({
  categories: {
    type: Array,
    default: () => []
  },
  selectedCat: {
    type: [Number, String, null],
    default: null
  }
})

defineEmits(['select-category', 'open-merchant'])

const getCatIcon = (name) => {
  if (!name) return '📦'
  if (name.includes('数码') || name.includes('硬件')) return '💻'
  if (name.includes('服装') || name.includes('鞋包')) return '👕'
  if (name.includes('日用') || name.includes('百货')) return '🏠'
  if (name.includes('食品') || name.includes('零食')) return '🍪'
  if (name.includes('权益') || name.includes('卡券')) return '⚡'
  return '📦'
}
</script>
