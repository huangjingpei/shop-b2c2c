<!--
文件：StatusPage.vue
用途：管理端403、404与身份服务暂不可用的独立状态页。
维护注意：不展示缓存身份或受保护数据，文案由静态路由配置提供；重试必须重新经过服务端身份校验，不可绕过路由守卫。
-->
<template>
  <main class="status-page">
    <h1>{{ code }}</h1>
    <h2>{{ title }}</h2>
    <p>{{ message }}</p>
    <router-link to="/">{{ code === '503' ? '重新验证登录状态' : '返回首页' }}</router-link>
    <router-link to="/login">前往登录</router-link>
  </main>
</template>
<script setup>
defineProps({ code: String, title: String, message: String })
</script>
<style scoped>
.status-page { max-width: 640px; margin: 14vh auto; padding: 32px; text-align: center; color: #334155; }
h1 { font-size: 56px; margin: 0; color: #2563eb; }
p { line-height: 1.8; }
a { display: inline-block; margin: 12px; color: #2563eb; }
</style>
