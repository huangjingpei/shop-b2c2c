/*
 * 文件：main.js
 * 用途：Vue应用启动并注册路由、状态管理。
 * 维护注意：插件注册顺序与app挂载节点应保持一致；新增全局依赖须核对打包和初始化副作用，权限不得仅由前端状态判断。
 */
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
