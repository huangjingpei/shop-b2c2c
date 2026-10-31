<template>
  <header class="bg-white border-b border-slate-200 sticky top-0 z-50">
    <!-- 顶部微型服务导航栏 -->
    <div class="bg-slate-900 text-slate-300 text-xs py-1.5">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 flex items-center justify-between">
        <div class="flex items-center space-x-3">
          <span class="text-orange-400 font-bold">16688</span>
          <span class="text-slate-500">|</span>
          <span class="truncate">专业数字卡密与全品类源头货源直采中心 · 0元开店 · 免费直清开户</span>
        </div>
        <div class="flex items-center space-x-4 text-[11px] shrink-0">
          <router-link to="/quick-query" class="text-orange-400 hover:text-orange-300 flex items-center space-x-1 font-bold">
            <span>⚡ 免登录极速查单</span>
          </router-link>
          <span class="text-slate-700">|</span>
          <a href="http://localhost:3001" target="_blank" class="hover:text-white transition flex items-center space-x-1">
            <span>🏪 商家工作台</span>
          </a>
          <span class="text-slate-700">|</span>
          <a href="http://localhost:3001" target="_blank" class="hover:text-white transition flex items-center space-x-1">
            <span>👑 平台管理中心</span>
          </a>
        </div>
      </div>
    </div>

    <!-- 16688 经典品牌搜索主栏 (带官方 search-header-bg) -->
    <div 
      class="border-b border-slate-100 bg-cover bg-right-bottom relative"
      :style="{ backgroundImage: 'url(' + searchBg + ')' }"
    >
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-4 flex flex-col md:flex-row md:items-center justify-between gap-4">
        <!-- 品牌 Logo -->
        <router-link to="/" class="flex items-center space-x-3 shrink-0">
          <div class="w-12 h-12 rounded-xl bg-gradient-to-tr from-orange-600 to-amber-500 flex items-center justify-center text-white font-black text-2xl shadow-md tracking-tighter">
            16
          </div>
          <div>
            <div class="flex items-baseline space-x-1">
              <span class="text-2xl font-black text-slate-900 tracking-tight">16688</span>
              <span class="text-sm font-bold text-orange-600 bg-orange-50 px-1.5 py-0.2 rounded border border-orange-200">货源网</span>
            </div>
            <span class="text-[11px] block text-slate-500 font-medium tracking-wide">
              全品类货源直发 · 数字权益秒提
            </span>
          </div>
        </router-link>

        <!-- 居中 16688 风格搜索框 -->
        <div class="flex-1 max-w-2xl">
          <div class="flex rounded-lg overflow-hidden border-2 border-orange-500 shadow-sm focus-within:ring-2 focus-within:ring-orange-400/40 bg-white">
            <select v-model="searchType" class="bg-slate-50 text-xs font-semibold text-slate-700 px-3 border-r border-slate-200 outline-none cursor-pointer">
              <option value="all">全站货源</option>
              <option value="physical">实物现货</option>
              <option value="virtual">数字权益</option>
            </select>
            <input
              v-model="keyword"
              type="text"
              placeholder="搜索全品类源头货源、数码潮品、服装鞋包、数字卡券或货号..."
              class="flex-1 px-4 py-2.5 text-sm text-slate-800 outline-none"
              @keyup.enter="handleSearch"
            />
            <button
              @click="handleSearch"
              class="bg-orange-600 hover:bg-orange-700 text-white font-bold px-6 text-sm transition flex items-center space-x-1"
            >
              <span>搜索</span>
            </button>
          </div>

          <!-- 热门搜索推荐词 -->
          <div class="flex items-center space-x-2 mt-1.5 text-[11px] text-slate-500 overflow-hidden">
            <span class="shrink-0 text-slate-400">热搜：</span>
            <button @click="setSearch('蓝牙耳机')" class="hover:text-orange-600 transition">降噪蓝牙耳机</button>
            <span>·</span>
            <button @click="setSearch('T恤')" class="hover:text-orange-600 transition">重磅纯棉T恤</button>
            <span>·</span>
            <button @click="setSearch('腾讯视频')" class="hover:text-orange-600 transition">腾讯视频VIP</button>
            <span>·</span>
            <button @click="setSearch('坚果')" class="hover:text-orange-600 transition">每日坚果礼盒</button>
            <span>·</span>
            <button @click="setSearch('键帽')" class="hover:text-orange-600 transition">客制化PBT键帽</button>
          </div>
        </div>

        <!-- 右侧快速行动区 -->
        <div class="shrink-0 flex items-center space-x-2.5">
          <router-link
            to="/quick-query"
            class="px-4 py-2 rounded-lg bg-orange-50 hover:bg-orange-100 text-orange-700 font-bold text-xs border border-orange-200 transition flex items-center space-x-1.5 shadow-xs"
          >
            <span>⚡</span>
            <span>免登录查单</span>
          </router-link>
          <a
            href="http://localhost:3001"
            target="_blank"
            class="px-4 py-2 rounded-lg bg-slate-900 hover:bg-slate-800 text-white font-bold text-xs transition shadow-xs flex items-center space-x-1"
          >
            <span>0元入驻开店</span>
          </a>
        </div>
      </div>
    </div>

    <!-- 16688 经典导航条 -->
    <div class="bg-slate-900 text-white text-xs font-bold">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 flex items-center justify-between">
        <div class="flex items-center space-x-1">
          <router-link to="/" class="px-5 py-2.5 bg-orange-600 text-white flex items-center space-x-1.5 hover:bg-orange-700 transition">
            <span>☰</span>
            <span>全部货源分类</span>
          </router-link>
          <router-link to="/" class="px-4 py-2.5 hover:bg-slate-800 transition text-slate-200">
            首页
          </router-link>
          <router-link to="/" class="px-4 py-2.5 hover:bg-slate-800 transition text-slate-200">
            货源广场
          </router-link>
          <router-link to="/quick-query" class="px-4 py-2.5 hover:bg-slate-800 transition text-slate-200">
            密价采购 & 查单
          </router-link>
          <a href="http://localhost:3001" target="_blank" class="px-4 py-2.5 hover:bg-slate-800 transition text-orange-400">
            商家入驻 (免营业执照)
          </a>
        </div>

        <div class="hidden lg:flex items-center space-x-4 text-slate-400 text-[11px] font-normal">
          <span>官方直付通直连结算 · 绝对零二清</span>
        </div>
      </div>
    </div>
  </header>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import searchBg from '../assets/16688/search-header-bg.png'

const router = useRouter()
const searchType = ref('all')
const keyword = ref('')

const handleSearch = () => {
  if (!keyword.value.trim()) return
  router.push({
    path: '/',
    query: { keyword: keyword.value.trim() }
  })
}

const setSearch = (val) => {
  keyword.value = val
  handleSearch()
}
</script>
