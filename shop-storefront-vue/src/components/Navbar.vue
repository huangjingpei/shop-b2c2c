<template>
  <header class="bg-white/95 backdrop-blur-md border-b border-slate-100 sticky top-0 z-50 transition-all">
    <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-18 flex items-center justify-between">
      <!-- 左侧: 16688 官方 Logo + 标语 -->
      <div class="flex items-center space-x-6">
        <router-link to="/" class="flex items-center space-x-2.5 group">
          <div class="text-3xl font-black text-orange-600 tracking-tighter flex items-center">
            <span>16688</span>
          </div>
          <div class="flex flex-col text-[10px] leading-tight text-slate-400 border-l border-slate-200 pl-2.5">
            <span class="text-slate-500 font-medium">轻钛旗下 &gt;</span>
            <span>专业数字卡密自动交易平台</span>
          </div>
        </router-link>

        <!-- 中间主要导航项 -->
        <nav class="hidden md:flex items-center space-x-7 text-sm font-medium">
          <router-link
            to="/"
            class="text-blue-600 font-bold border-b-2 border-blue-600 pb-0.5 transition"
          >
            首页
          </router-link>
          <a href="#services" class="text-slate-600 hover:text-blue-600 transition">
            产品服务
          </a>
          <a href="#guide" class="text-slate-600 hover:text-blue-600 transition">
            开店指引
          </a>
          <router-link to="/quick-query" class="text-slate-600 hover:text-blue-600 transition">
            联系我们
          </router-link>
          <a
            href="javascript:void(0)"
            @click="showReport = true"
            class="text-red-500 hover:text-red-600 font-medium transition"
          >
            举报违规商家
          </a>
        </nav>
      </div>

      <!-- 右侧用户操作区 -->
      <div class="flex items-center space-x-4">
        <!-- 快速查单胶囊 (对标买家便捷通道) -->
        <router-link
          to="/quick-query"
          class="hidden sm:inline-flex items-center space-x-1 text-xs text-slate-600 hover:text-blue-600 transition"
        >
          <span>⚡ 免登录查单</span>
        </router-link>

        <a
          href="http://localhost:3001"
          target="_blank"
          class="text-xs font-semibold text-slate-600 hover:text-blue-600 transition"
        >
          注册商家
        </a>

        <a
          href="http://localhost:3001"
          target="_blank"
          class="px-5 py-2 rounded-full bg-blue-600 hover:bg-blue-700 text-white font-bold text-xs shadow-sm hover:shadow transition-all duration-200 flex items-center space-x-1"
        >
          <span>已入驻，登录</span>
        </a>
      </div>
    </div>
  </header>
</template>

<script setup>
import { ref } from 'vue'

const showReport = ref(false)
</script>
