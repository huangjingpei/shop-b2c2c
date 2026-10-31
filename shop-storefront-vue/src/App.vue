<template>
  <div class="min-h-screen flex flex-col bg-slate-50/60 font-sans">
    <Navbar />
    <main class="flex-1 max-w-7xl w-full mx-auto px-4 sm:px-6 lg:px-8 py-6">
      <router-view />
    </main>
    <SiteFooter />
  </div>
</template>

<script setup>
import Navbar from './components/Navbar.vue'
import SiteFooter from './components/common/SiteFooter.vue'
</script>
