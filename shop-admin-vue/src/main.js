/*
 * 文件：main.js
 * 用途：Vue应用启动并注册路由、状态管理和Element Plus。
 * 维护注意：跨标签页token变化触发重新验证或离开私有页面；插件注册顺序与app挂载节点应保持一致；新增全局依赖须核对打包和初始化副作用，权限不得仅由前端状态判断。
 */
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { storageNavigation } from './utils/authSession.mjs'

const app = createApp(App)

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus)

app.mount('#app')

// Other tabs must unmount protected pages after logout or revalidate a replacement login.
window.addEventListener('storage', event => {
  const destination = storageNavigation(event)
  if (destination) router.replace(destination)
})
