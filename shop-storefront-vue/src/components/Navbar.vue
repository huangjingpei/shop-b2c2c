<template>
  <header class="bg-white border-b border-slate-200 sticky top-0 z-50">
    <!-- 顶部微型服务栏 -->
    <div class="bg-slate-100/80 border-b border-slate-200/60 text-xs text-slate-500 py-1.5">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 flex items-center justify-between">
        <div class="flex items-center space-x-3">
          <span>欢迎来到 16688 全品类电商与源头货源批发网！</span>
          <span class="text-slate-300">|</span>
          <span class="text-orange-600 font-medium">支持个人免照开店 · 资金微信直付通存管</span>
        </div>
        <div class="flex items-center space-x-4">
          <router-link to="/quick-query" class="hover:text-orange-600 flex items-center space-x-1 font-medium text-slate-700">
            <span>⚡ 免登录极速查单</span>
          </router-link>
          <span class="text-slate-300">|</span>
          <a href="/console/" target="_blank" class="hover:text-orange-600 flex items-center space-x-1">
            <span>🏪 商家工作台</span>
          </a>
          <span class="text-slate-300">|</span>
          <a href="/console/" target="_blank" class="hover:text-orange-600 flex items-center space-x-1">
            <span>👑 平台管理中心</span>
          </a>
        </div>
      </div>
    </div>

    <!-- 主搜索与品牌栏 (16688 经典头部) -->
    <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-3.5 flex items-center justify-between gap-6">
      <!-- 品牌 Logo -->
      <router-link to="/" class="flex items-center space-x-2.5 shrink-0">
        <div class="w-11 h-11 rounded-xl bg-gradient-to-tr from-orange-600 to-amber-500 flex items-center justify-center text-white font-black text-2xl shadow-sm tracking-tighter">
          16
        </div>
        <div>
          <div class="flex items-baseline space-x-1">
            <span class="text-2xl font-black text-slate-900 tracking-tight">16688</span>
            <span class="text-lg font-bold text-orange-600">货源网</span>
          </div>
          <span class="text-[10px] block text-slate-400 font-mono tracking-wider -mt-0.5">全品类现货批发 · 数字权益</span>
        </div>
      </router-link>

      <!-- 居中巨型搜索框 -->
      <div class="flex-1 max-w-2xl">
        <div class="flex rounded-lg overflow-hidden border-2 border-orange-500 shadow-sm focus-within:ring-2 focus-within:ring-orange-400/40">
          <select v-model="searchType" class="bg-slate-50 text-xs font-semibold text-slate-700 px-3 border-r border-slate-200 outline-none cursor-pointer">
            <option value="all">全站货源</option>
            <option value="physical">实物现货</option>
            <option value="virtual">数字权益</option>
          </select>
          <input
            v-model="keyword"
            type="text"
            placeholder="搜索全品类商品、数码潮品、服装鞋包、数字卡券或货源编码..."
            class="flex-1 px-4 py-2 text-sm text-slate-800 outline-none"
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
        <div class="flex items-center space-x-2 mt-1.5 text-xs text-slate-400 overflow-hidden">
          <span class="shrink-0 text-slate-500 font-medium">热门：</span>
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

      <!-- 右侧查单专属快捷胶囊 -->
      <div class="shrink-0 hidden md:flex items-center space-x-3">
        <router-link
          to="/quick-query"
          class="px-4 py-2 rounded-lg bg-orange-50 hover:bg-orange-100 text-orange-700 font-bold text-xs border border-orange-200/80 transition flex items-center space-x-1.5 shadow-sm"
        >
          <span class="text-base">⚡</span>
          <span>免登录快速查单</span>
        </router-link>
      </div>
    </div>

    <!-- 主导航条 -->
    <div class="bg-slate-900 text-white text-sm font-semibold">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 flex items-center">
        <!-- 分类导航悬浮按钮 -->
        <div class="bg-orange-600 text-white px-5 py-2.5 flex items-center space-x-2 font-bold cursor-pointer">
          <span>☰ 全部商品分类</span>
        </div>

        <!-- 栏目导航链接 -->
        <nav class="flex items-center space-x-8 px-6 text-slate-200 text-xs font-medium">
          <router-link to="/" class="hover:text-white py-2.5 transition text-white font-bold">全品类大厅</router-link>
          <router-link to="/" class="hover:text-white py-2.5 transition">源头厂货直发</router-link>
          <router-link to="/" class="hover:text-white py-2.5 transition">3C数码潮品</router-link>
          <router-link to="/" class="hover:text-white py-2.5 transition">服装鞋包百货</router-link>
          <router-link to="/" class="hover:text-white py-2.5 transition">食品生鲜批发</router-link>
          <router-link to="/" class="hover:text-white py-2.5 transition text-amber-300 font-semibold">⚡ 虚拟数字权益(秒发)</router-link>
        </nav>
      </div>
    </div>
  </header>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const searchType = ref('all')
const keyword = ref('')

const handleSearch = () => {
  if (keyword.value.trim()) {
    router.push({ path: '/', query: { q: keyword.value.trim() } })
  } else {
    router.push({ path: '/' })
  }
}

const setSearch = (w) => {
  keyword.value = w
  handleSearch()
}
</script>
