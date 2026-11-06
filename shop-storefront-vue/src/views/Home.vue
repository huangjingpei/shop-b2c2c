<!--
文件：Home.vue
用途：商城频道与商品浏览入口。
维护注意：
1. 分类入口已收敛为顶部导航一处，本页不再重复渲染类目树或类目标签行；频道选中态与筛选由 route.query.channel 驱动。
2. 类型3须标识人工交付，不能宣传自动发卡；列表可见性和库存仅作展示，交易资格与最终库存由后端裁决。
3. 读取 route.query 的 q/channel/goodsType 并随频道条件请求后端，业务失败显示错误与重试而不是空列表；递增序号作废迟到响应。
4. 页面不得再宣传未开通的支付直结、资金存管、免照开店或自动秒发能力。
-->
<template>
  <div class="space-y-6">
    <!-- 首页黄金区 (中间轮播Banner + 右侧快捷入驻/查单卡片) -->
    <div class="grid grid-cols-1 lg:grid-cols-12 gap-5">
      <!-- 中间品牌营销大横幅 (宽 8 列) -->
      <div class="lg:col-span-8 bg-gradient-to-tr from-slate-900 via-indigo-950 to-slate-900 rounded-xl text-white p-6 md:p-8 flex flex-col justify-between shadow-sm relative overflow-hidden">
        <div class="relative z-10 max-w-lg">
          <span class="inline-block px-2.5 py-0.5 rounded-full bg-orange-500/20 text-orange-400 text-xs font-bold uppercase tracking-wider mb-3 border border-orange-500/30">
            全品类货源直采中心
          </span>
          <h1 class="text-2xl md:text-3xl font-black tracking-tight leading-tight">
            源头厂家现货直供<br />
            <span class="text-orange-400">实物批发 & 虚拟数字权益</span>
          </h1>
          <p class="mt-2.5 text-xs text-slate-300 leading-relaxed">
            支持单件起订、多规格变体直发；小微商家自主入驻展示，规范交易与代付保障。
          </p>

          <div class="mt-6 flex flex-wrap gap-2.5">
            <router-link to="/" class="px-4 py-2 rounded-lg bg-orange-500 hover:bg-orange-600 text-white font-bold text-xs transition shadow-sm">
              浏览全品类货源
            </router-link>
            <router-link to="/quick-query" class="px-4 py-2 rounded-lg bg-white/10 hover:bg-white/20 text-white font-semibold text-xs backdrop-blur-sm transition">
              免登录提取卡密/查单 ›
            </router-link>
          </div>
        </div>

        <!-- 装饰性渐变光晕与标识 -->
        <div class="absolute -right-10 -bottom-10 w-64 h-64 bg-orange-500/10 rounded-full blur-3xl pointer-events-none"></div>
      </div>

      <!-- 右侧快捷工作台与保障 (宽 4 列) -->
      <div class="lg:col-span-4 flex flex-col gap-4">
        <!-- 商家与买家快捷卡片 -->
        <div class="bg-white rounded-xl border border-slate-200/80 p-5 shadow-sm flex flex-col justify-between flex-1">
          <div>
            <div class="flex items-center space-x-3 mb-3">
              <div class="w-10 h-10 rounded-full bg-orange-100 flex items-center justify-center text-xl text-orange-600 font-black">
                🛍️
              </div>
              <div>
                <div class="text-xs font-bold text-slate-900">16688 商家与买家中枢</div>
                <div class="text-[11px] text-slate-400">商家自主入驻展示 · 资质以平台审核为准</div>
              </div>
            </div>

            <div class="space-y-2 mt-4">
              <a
                :href="merchantPortalUrl"
                target="_blank"
                rel="noopener"
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
              <span>支付通道暂未开放，下单不会实际扣款</span>
            </div>
            <div class="flex items-center space-x-1.5 text-slate-700 font-medium">
              <span class="text-green-600">✓</span>
              <span>履约方式以商品类型与商家说明为准</span>
            </div>
            <div class="flex items-center space-x-1.5 text-slate-700 font-medium">
              <span class="text-green-600">✓</span>
              <span>入驻资质与审核结果以平台规则为准</span>
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
          <div class="font-bold text-xs text-slate-900">虚拟卡密商品</div>
          <div class="text-[11px] text-slate-400 mt-0.5">交付以商家规则与付款状态为准</div>
        </div>
      </div>

      <div class="bg-white rounded-xl border border-slate-200/80 p-4 flex items-center space-x-3.5 shadow-sm">
        <div class="w-10 h-10 rounded-lg bg-emerald-50 text-emerald-600 flex items-center justify-center text-xl shrink-0">
          🛡️
        </div>
        <div>
          <div class="font-bold text-xs text-slate-900">订单状态可追溯</div>
          <div class="text-[11px] text-slate-400 mt-0.5">支付与结算能力仍在筹备中</div>
        </div>
      </div>

      <div class="bg-white rounded-xl border border-slate-200/80 p-4 flex items-center space-x-3.5 shadow-sm">
        <div class="w-10 h-10 rounded-lg bg-purple-50 text-purple-600 flex items-center justify-center text-xl shrink-0">
          📦
        </div>
        <div>
          <div class="font-bold text-xs text-slate-900">实物商品</div>
          <div class="text-[11px] text-slate-400 mt-0.5">物流方式以商家发货信息为准</div>
        </div>
      </div>
    </div>

    <!-- 当前频道说明条：分类入口只在顶部导航，这里只展示当前筛选结果与返回入口 -->
    <div class="bg-white rounded-xl border border-slate-200/80 p-4 shadow-sm flex flex-col md:flex-row md:items-center justify-between gap-3">
      <div class="flex items-center flex-wrap gap-x-3 gap-y-1">
        <span class="text-sm font-black text-slate-900">{{ activeChannel.channelLabel }}</span>
        <span class="text-xs text-slate-400">
          <span v-if="searchKeyword">关键词“<strong class="text-slate-800">{{ searchKeyword }}</strong>” · </span>
          共 <strong class="text-slate-800">{{ products.length }}</strong> 款货源
        </span>
      </div>
      <router-link
        v-if="hasFilter"
        to="/"
        class="text-xs font-bold text-orange-600 hover:text-orange-700 shrink-0"
      >
        返回全品类大厅 ›
      </router-link>
    </div>

    <!-- 16688 经典高密度商品卡片流 (严格遵循 16688 UI/UX) -->
    <div v-if="errorMsg" class="text-center py-16 bg-white rounded-xl border border-rose-200">
      <div class="text-3xl mb-2">⚠️</div>
      <p class="text-rose-600 text-sm font-medium">{{ errorMsg }}</p>
      <button
        @click="loadData()"
        class="mt-3 px-4 py-1.5 rounded-lg bg-orange-600 hover:bg-orange-700 text-white text-xs font-bold transition"
      >
        重新加载
      </button>
    </div>
    <div v-else-if="loading" class="text-center py-16 text-slate-400 text-sm">
      正在检索全平台货源数据...
    </div>
    <div v-else-if="products.length === 0" class="text-center py-20 bg-white rounded-xl border border-slate-200">
      <div class="text-3xl mb-2">📦</div>
      <p class="text-slate-500 text-sm font-medium">
        {{ searchKeyword
          ? `未找到与“${searchKeyword}”匹配的货源，请更换关键词`
          : activeChannel.channelKey === 'all'
            ? '当前暂无在售商品，请稍后再来'
            : `“${activeChannel.channelLabel}”频道下暂无在售商品` }}
      </p>
      <button
        v-if="hasFilter"
        @click="clearFilters"
        class="mt-3 px-4 py-1.5 rounded-lg bg-slate-100 hover:bg-slate-200 text-slate-700 text-xs font-bold transition"
      >
        查看全品类
      </button>
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
            <span v-if="p.goodsType === 3" class="text-xs font-bold text-blue-700">定制服务 · 人工交付</span>
            <span
              v-if="p.goodsType === 2"
              class="px-2 py-0.5 rounded text-[11px] font-bold bg-amber-500 text-white shadow-sm flex items-center space-x-1"
            >
              <span>虚拟卡密商品</span>
            </span>
            <span
              v-else-if="p.goodsType === 1"
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
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import axios from 'axios'
import { loadPublicCatalog, readCatalogQuery } from '../utils/publicCatalog.mjs'
import { resolveMerchantPortalUrl } from '../utils/portalLinks.mjs'

const route = useRoute()
const router = useRouter()
const merchantPortalUrl = resolveMerchantPortalUrl({
  configured: import.meta.env.VITE_MERCHANT_PORTAL_URL,
  dev: import.meta.env.DEV,
  protocol: window.location.protocol,
  hostname: window.location.hostname
})
const loading = ref(false)
const errorMsg = ref('')
const products = ref([])
const catalogQuery = computed(() => readCatalogQuery(route.query))
const activeChannel = catalogQuery
const searchKeyword = computed(() => catalogQuery.value.keyword)
const hasFilter = computed(() => catalogQuery.value.channelKey !== 'all' || !!catalogQuery.value.keyword)
let requestSequence = 0

const loadData = async () => {
  const sequence = ++requestSequence
  loading.value = true
  errorMsg.value = ''
  const query = catalogQuery.value
  const result = await loadPublicCatalog(
    { categoryIds: query.categoryIds, keyword: query.keyword, goodsType: query.goodsType },
    (url, config) => axios.get(url, config)
  )
  // 条件已变化时丢弃迟到响应，避免旧请求覆盖新频道/新搜索的错误态或列表。
  if (sequence !== requestSequence) return
  products.value = result.products
  errorMsg.value = result.error
  loading.value = false
}

const clearFilters = () => {
  router.push({ path: '/' })
}

watch(
  () => [route.query.q, route.query.channel, route.query.goodsType],
  () => {
    loadData()
  }
)

onMounted(() => {
  loadData()
})
</script>
