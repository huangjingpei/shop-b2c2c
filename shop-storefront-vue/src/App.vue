<template>
  <div v-if="route.meta?.pureLayout" class="min-h-screen bg-[#F5F5F7]">
    <router-view />
  </div>
  <div v-else class="min-h-screen flex flex-col">
    <Navbar />
    <main class="flex-1 max-w-7xl w-full mx-auto px-4 sm:px-6 lg:px-8 py-6">
      <router-view />
    </main>
    <footer class="bg-white border-t border-slate-200 py-6 text-center text-xs text-slate-500">
      <div class="max-w-7xl mx-auto px-4">
        <p>© 2026 16688 货源优选交易平台 · 依托《电商法》第十条个人免照开店 · 资金微信直付通专户二级分账（零二清）</p>
        <p class="mt-1 text-slate-400">对标 16688 模式 · 实物电商 & 虚拟数字权益秒发</p>
      </div>
    </footer>
  </div>
</template>

<script setup>
import { useRoute } from 'vue-router'
import Navbar from './components/Navbar.vue'

const route = useRoute()
</script>
