/*
 * 文件：index.js
 * 用途：页面路由及导航策略配置。
 * 维护注意：history模式依赖生产代理回退与正确base；前端路由保护不替代API授权，404/角色处理仍有历史待完善项。
 */
import { createRouter, createWebHistory } from 'vue-router'
import Home from '../views/Home.vue'
import Store from '../views/Store.vue'
import ProductDetail from '../views/ProductDetail.vue'
import QuickQuery from '../views/QuickQuery.vue'

const routes = [
  {
    path: '/',
    name: 'Home',
    component: Home
  },
  {
    path: '/store/:id',
    name: 'Store',
    component: Store
  },
  {
    path: '/product/:id',
    name: 'ProductDetail',
    component: ProductDetail
  },
  {
    path: '/quick-query',
    name: 'QuickQuery',
    component: QuickQuery
  },
  {
    path: '/peer-pay/:token',
    name: 'PeerPayCashier',
    component: () => import('../views/PeerPayCashier.vue'),
    meta: { pureLayout: true }
  }
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes
})

export default router
