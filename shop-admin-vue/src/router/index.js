import { createRouter, createWebHistory } from 'vue-router'
import AdminLayout from '../views/layout/AdminLayout.vue'
import Login from '../views/login/Login.vue'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: Login
  },
  {
    path: '/',
    component: AdminLayout,
    redirect: '/admin/dashboard',
    children: [
      // 平台超级管理员视角
      {
        path: '/admin/dashboard',
        name: 'AdminDashboard',
        component: () => import('../views/admin/Dashboard.vue'),
        meta: { title: '平台数据大盘', role: 'SUPERADMIN' }
      },
      {
        path: '/admin/merchants',
        name: 'AdminMerchants',
        component: () => import('../views/admin/Merchants.vue'),
        meta: { title: '入驻商户治理', role: 'SUPERADMIN' }
      },
      {
        path: '/admin/categories',
        name: 'AdminCategories',
        component: () => import('../views/admin/Categories.vue'),
        meta: { title: '统一商品类目', role: 'SUPERADMIN' }
      },
      {
        path: '/admin/orders',
        name: 'AdminOrders',
        component: () => import('../views/admin/Orders.vue'),
        meta: { title: '全平台交易监察', role: 'SUPERADMIN' }
      },

      // 商家工作台视角
      {
        path: '/merchant/dashboard',
        name: 'MerchantDashboard',
        component: () => import('../views/merchant/Dashboard.vue'),
        meta: { title: '店铺经营看板' }
      },
      {
        path: '/merchant/profile',
        name: 'MerchantProfile',
        component: () => import('../views/merchant/Profile.vue'),
        meta: { title: '店铺资料与分账卡' }
      },
      {
        path: '/merchant/products',
        name: 'MerchantProducts',
        component: () => import('../views/merchant/Products.vue'),
        meta: { title: '商品与规格变体' }
      },
      {
        path: '/merchant/cards',
        name: 'MerchantCards',
        component: () => import('../views/merchant/Cards.vue'),
        meta: { title: '16688 虚拟卡密库' }
      },
      {
        path: '/merchant/orders',
        name: 'MerchantOrders',
        component: () => import('../views/merchant/Orders.vue'),
        meta: { title: '本店订单与履约' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('token')
  if (to.path !== '/login' && !token) {
    next('/login')
  } else {
    next()
  }
})

export default router
