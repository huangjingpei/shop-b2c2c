/*
 * 文件：main.js
 * 用途：Vue应用启动并注册路由、状态管理和Element Plus。
 * 维护注意：跨标签页token变化触发重新验证或离开私有页面；插件注册顺序与app挂载节点应保持一致；新增全局依赖须核对打包和初始化副作用，权限不得仅由前端状态判断。
 * 会话隔离：storage事件只响应当前页面视角对应桶（/admin/**→超管桶，其余→商家桶）的token变化；另一桶的登录/登出不导航，两个后台互不干扰。
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
// 只监听当前页面视角对应桶的 token 变化；另一桶（超管/商家）的登录登出与本页无关。
window.addEventListener('storage', event => {
  const perspective = router.currentRoute.value.path.startsWith('/admin/') ? 'ADMIN' : 'MERCHANT'
  const destination = storageNavigation(event, perspective)
  if (destination) router.replace(destination)
})
