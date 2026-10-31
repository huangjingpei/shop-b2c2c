<template>
  <div class="space-y-6">
    <!-- 首页黄金三角区 (左侧品类树 + 中间轮播Banner + 右侧快捷入驻/查单卡片) -->
    <div class="grid grid-cols-1 lg:grid-cols-12 gap-5">
      <!-- 左侧全品类树导航 (宽 3 列) -->
      <div class="hidden lg:block lg:col-span-3 bg-white rounded-xl border border-slate-200/80 p-3 shadow-sm">
        <div class="text-xs font-bold text-slate-800 uppercase px-3 py-2 border-b border-slate-100 flex items-center justify-between">
          <span>行业主营类目</span>
          <span class="text-[10px] text-orange-600 font-normal">全网严选源头</span>
        </div>
        <div class="divide-y divide-slate-50 mt-1">
          <button
            v-for="cat in categories"
            :key="cat.id"
            @click="selectCategory(cat.id)"
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

      <!-- 中间品牌营销大横幅 (宽 6 列) -->
      <div class="lg:col-span-6 bg-gradient-to-tr from-slate-900 via-indigo-950 to-slate-900 rounded-xl text-white p-6 md:p-8 flex flex-col justify-between shadow-sm relative overflow-hidden">
        <div class="relative z-10 max-w-lg">
          <span class="inline-block px-2.5 py-0.5 rounded-full bg-orange-500/20 text-orange-400 text-xs font-bold uppercase tracking-wider mb-3 border border-orange-500/30">
            全品类货源直采中心
          </span>
          <h1 class="text-2xl md:text-3xl font-black tracking-tight leading-tight">
            源头厂家现货直供<br />
            <span class="text-orange-400">实物批发 & 虚拟数字权益秒发</span>
          </h1>
          <p class="mt-2.5 text-xs text-slate-300 leading-relaxed">
            支持单件起批、一件代发、阶梯批发价与多规格变体；小微个人免营业执照开店，资金直通官方存管备付金专户（零二清）。
          </p>

          <div class="mt-6 flex flex-wrap gap-2.5">
            <button @click="selectCategory(null)" class="px-4 py-2 rounded-lg bg-orange-500 hover:bg-orange-600 text-white font-bold text-xs transition shadow-sm">
              浏览全品类货源
            </button>
            <router-link to="/quick-query" class="px-4 py-2 rounded-lg bg-white/10 hover:bg-white/20 text-white font-semibold text-xs backdrop-blur-sm transition">
              免登录提取卡密/查单 ›
            </router-link>
          </div>
        </div>

        <!-- 装饰性渐变光晕与标识 -->
        <div class="absolute -right-10 -bottom-10 w-64 h-64 bg-orange-500/10 rounded-full blur-3xl pointer-events-none"></div>
      </div>

      <!-- 右侧快捷工作台与保障 (宽 3 列) -->
      <div class="lg:col-span-3 flex flex-col gap-4">
        <!-- 商家与买家快捷卡片 -->
        <div class="bg-white rounded-xl border border-slate-200/80 p-5 shadow-sm flex flex-col justify-between flex-1">
          <div>
            <div class="flex items-center space-x-3 mb-3">
              <div class="w-10 h-10 rounded-full bg-orange-100 flex items-center justify-center text-xl text-orange-600 font-black">
                🛍️
              </div>
              <div>
                <div class="text-xs font-bold text-slate-900">16688 商家与买家中枢</div>
                <div class="text-[11px] text-slate-400">支持个人免照开店入驻</div>
              </div>
            </div>

            <div class="space-y-2 mt-4">
              <a
                href="http://localhost:3001"
                target="_blank"
                class="w-full block text-center py-2 rounded-lg bg-slate-900 hover:bg-slate-800 text-white text-xs font-bold transition shadow-sm"
              >
                进入商家工作台 / 开店
              </a>
              <router-link
                to="/quick-query"
                class="w-full block text-center py-2 rounded-lg bg-orange-50 hover:bg-orange-100 text-orange-700 text-xs font-bold border border-orange-200 transition"
              >
                ⚡ 免登录极速查单
              </router-link>
            </div>
          </div>

          <!-- 平台核心保障 -->
          <div class="mt-4 pt-3 border-t border-slate-100 text-[11px] text-slate-500 space-y-1">
            <div class="flex items-center space-x-1.5 text-slate-700 font-medium">
              <span class="text-green-600">✓</span>
              <span>微信/支付宝官方二级直结 (零二清)</span>
            </div>
            <div class="flex items-center space-x-1.5 text-slate-700 font-medium">
              <span class="text-green-600">✓</span>
              <span>实物顺丰发货 / 虚拟卡密自动秒发</span>
            </div>
            <div class="flex items-center space-x-1.5 text-slate-700 font-medium">
              <span class="text-green-600">✓</span>
              <span>依据《电商法》第十条个人免照开店</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 16688 核心服务金刚区 (4大金牌保障) -->
    <div class="grid grid-cols-2 md:grid-cols-4 gap-4">
      <div class="bg-white rounded-xl border border-slate-200/80 p-4 flex items-center space-x-3.5 shadow-sm">
        <div class="w-10 h-10 rounded-lg bg-blue-50 text-blue-600 flex items-center justify-center text-xl shrink-0">
          🏭
        </div>
        <div>
          <div class="font-bold text-xs text-slate-900">源头货源直发</div>
          <div class="text-[11px] text-slate-400 mt-0.5">厂家直采 挤出中间商差价</div>
        </div>
      </div>

      <div class="bg-white rounded-xl border border-slate-200/80 p-4 flex items-center space-x-3.5 shadow-sm">
        <div class="w-10 h-10 rounded-lg bg-amber-50 text-amber-600 flex items-center justify-center text-xl shrink-0">
          ⚡
        </div>
        <div>
          <div class="font-bold text-xs text-slate-900">虚拟卡密自动秒发</div>
          <div class="text-[11px] text-slate-400 mt-0.5">付款成功即刻核销交货</div>
        </div>
      </div>

      <div class="bg-white rounded-xl border border-slate-200/80 p-4 flex items-center space-x-3.5 shadow-sm">
        <div class="w-10 h-10 rounded-lg bg-emerald-50 text-emerald-600 flex items-center justify-center text-xl shrink-0">
          🛡️
        </div>
        <div>
          <div class="font-bold text-xs text-slate-900">持牌官方专户存管</div>
          <div class="text-[11px] text-slate-400 mt-0.5">微信收付通二级分账 零二清</div>
        </div>
      </div>

      <div class="bg-white rounded-xl border border-slate-200/80 p-4 flex items-center space-x-3.5 shadow-sm">
        <div class="w-10 h-10 rounded-lg bg-purple-50 text-purple-600 flex items-center justify-center text-xl shrink-0">
          📦
        </div>
        <div>
          <div class="font-bold text-xs text-slate-900">现货实物发货</div>
          <div class="text-[11px] text-slate-400 mt-0.5">全国顺丰/圆通快递追踪</div>
        </div>
      </div>
    </div>

    <!-- 商品瀑布流控制台 (分类标签选择 + 数量展示) -->
    <div class="bg-white rounded-xl border border-slate-200/80 p-4 shadow-sm flex flex-col md:flex-row md:items-center justify-between gap-3">
      <div class="flex items-center space-x-2 overflow-x-auto">
        <button
          @click="selectCategory(null)"
          class="px-4 py-1.5 rounded-lg text-xs font-bold transition shrink-0"
          :class="selectedCat === null ? 'bg-orange-500 text-white' : 'bg-slate-100 text-slate-700 hover:bg-slate-200'"
        >
          全部商品 ({{ products.length }})
        </button>
        <button
          v-for="cat in categories"
          :key="cat.id"
          @click="selectCategory(cat.id)"
          class="px-4 py-1.5 rounded-lg text-xs font-bold transition shrink-0"
          :class="selectedCat === cat.id ? 'bg-orange-500 text-white' : 'bg-slate-100 text-slate-700 hover:bg-slate-200'"
        >
          {{ cat.name }}
        </button>
      </div>

      <div class="text-xs text-slate-400 text-right">
        共找到 <strong class="text-slate-800">{{ products.length }}</strong> 款精选源头现货
      </div>
    </div>

    <!-- 16688 经典高密度商品卡片流 (严格遵循 16688 UI/UX) -->
    <div v-if="loading" class="text-center py-16 text-slate-400 text-sm">
      正在检索全平台货源数据...
    </div>
    <div v-else-if="products.length === 0" class="text-center py-20 bg-white rounded-xl border border-slate-200">
      <div class="text-3xl mb-2">📦</div>
      <p class="text-slate-500 text-sm font-medium">该类目下暂无现货，正在火速补货中</p>
    </div>
    <div v-else class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-5">
      <div
        v-for="p in products"
        :key="p.id"
        class="bg-white rounded-xl border border-slate-200/90 hover:border-orange-400 hover:shadow-lg transition-all duration-200 flex flex-col overflow-hidden group"
      >
        <!-- 主图与属性徽标 -->
        <div class="relative aspect-video bg-slate-100 overflow-hidden">
          <img :src="p.mainImage" :alt="p.title" class="w-full h-full object-cover group-hover:scale-105 transition duration-300" />
          <div class="absolute top-2.5 left-2.5 flex space-x-1.5">
            <span
              v-if="p.goodsType === 2"
              class="px-2 py-0.5 rounded text-[11px] font-bold bg-amber-500 text-white shadow-sm flex items-center space-x-1"
            >
              <span>⚡ 自动秒发卡密</span>
            </span>
            <span
              v-else
              class="px-2 py-0.5 rounded text-[11px] font-bold bg-blue-600 text-white shadow-sm"
            >
              📦 实物现货发货
            </span>
          </div>
          <div class="absolute bottom-2 right-2 px-2 py-0.5 rounded bg-black/60 backdrop-blur-sm text-[10px] text-white">
            总可用现货: {{ p.totalStock }} 件
          </div>
        </div>

        <!-- 标题与详情 -->
        <div class="p-4 flex-1 flex flex-col justify-between">
          <div>
            <router-link :to="`/product/${p.id}`">
              <h3 class="font-bold text-slate-900 text-sm line-clamp-2 hover:text-orange-600 transition leading-snug">
                {{ p.title }}
              </h3>
            </router-link>
            <p class="text-xs text-slate-500 mt-1 line-clamp-1">{{ p.subTitle || '一手货源 品质保障' }}</p>
          </div>

          <div class="mt-4 pt-3 border-t border-slate-100">
            <!-- 商家资质与进店直达 -->
            <div class="flex items-center justify-between text-xs text-slate-500 mb-2">
              <div class="flex items-center space-x-1.5">
                <span class="text-sm">🏪</span>
                <span class="font-medium text-slate-800 truncate max-w-[130px]">{{ p.storeName }}</span>
              </div>
              <router-link :to="`/store/${p.merchantId}`" class="text-orange-600 font-semibold hover:underline">
                进店 ›
              </router-link>
            </div>

            <!-- 16688 阶梯起步价与采购按钮 -->
            <div class="flex items-baseline justify-between">
              <div>
                <span class="text-xs font-bold text-orange-600">￥</span>
                <span class="text-2xl font-black text-orange-600 tracking-tight">{{ p.minPrice }}</span>
                <span class="text-[11px] text-slate-400 ml-0.5">起批</span>
              </div>
              <router-link
                :to="`/product/${p.id}`"
                class="px-3.5 py-1.5 rounded-lg bg-orange-600 hover:bg-orange-700 text-white text-xs font-bold transition shadow-sm"
              >
                立即选购
              </router-link>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import axios from 'axios'

const loading = ref(false)
const selectedCat = ref(null)
const categories = ref([])
const products = ref([])

const getCatIcon = (name) => {
  if (name.includes('数码') || name.includes('硬件')) return '💻'
  if (name.includes('服装') || name.includes('鞋包')) return '👕'
  if (name.includes('日用') || name.includes('百货')) return '🏠'
  if (name.includes('食品') || name.includes('零食')) return '🍪'
  if (name.includes('权益') || name.includes('卡券')) return '⚡'
  return '📦'
}

const loadData = async () => {
  loading.value = true
  try {
    const [cRes, pRes] = await Promise.all([
      axios.get('/api/v1/public/categories'),
      axios.get('/api/v1/public/products', {
        params: { categoryId: selectedCat.value }
      })
    ])
    categories.value = cRes.data.data
    products.value = pRes.data.data
  } catch (e) {
    console.error(e)
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
