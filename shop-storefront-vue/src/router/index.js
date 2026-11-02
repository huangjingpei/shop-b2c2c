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
    component: () => import('../views/PeerPayCashier.vue')
  }
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes
})

export default router
