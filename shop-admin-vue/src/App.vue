<!--
文件：App.vue
用途：Vue根组件和公共布局出口。
维护注意：修改路由承载或代付独立布局时检查页面切换、全局样式与挂载顺序；根组件不应保存或公开敏感支付凭证。
-->
<template>
  <router-view />
</template>

<script setup>
</script>

<style>
body {
  margin: 0;
  padding: 0;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
  background-color: #f0f2f5;
  color: #333;
}
</style>
