/*
 * 文件：index.js
 * 用途：页面路由及导航策略配置。
 * 维护注意：history模式依赖生产代理回退与正确base；前端路由保护不替代API授权，每次受保护导航用/auth/me核验身份；403/404/验证故障页不渲染私有数据，本地角色不能作为授权。
 */
import { createRouter, createWebHistory } from 'vue-router'
import AdminLayout from '../views/layout/AdminLayout.vue'
import Login from '../views/login/Login.vue'
import request from '../api/request'
import { createSessionGuard } from '../utils/authSession.mjs'

const statusPage = () => import('../views/errors/StatusPage.vue')
const routes = [
  { path: '/forbidden', component: statusPage, meta: { public: true },
    props: { code: '403', title: '无访问权限', message: '当前账号无权访问该页面，或尚未绑定店铺。' } },
  { path: '/session-error', component: statusPage, meta: { public: true },
    props: { code: '503', title: '暂时无法验证登录状态', message: '请检查网络或稍后重试，确认身份后才能进入工作台。' } },
  { path: '/:pathMatch(.*)*', component: statusPage, meta: { public: true },
    props: { code: '404', title: '页面不存在', message: '请检查地址或返回首页。' } },
  {
    path: '/login',
    name: 'Login',
    component: Login,
    meta: { public: true }
  },
  {
    path: '/',
    component: AdminLayout,
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
        meta: { title: '店铺经营看板', requiresMerchant: true }
      },
      {
        path: '/merchant/profile',
        name: 'MerchantProfile',
        component: () => import('../views/merchant/Profile.vue'),
        meta: { title: '店铺资料与分账卡', requiresMerchant: true }
      },
      {
        path: '/merchant/products',
        name: 'MerchantProducts',
        component: () => import('../views/merchant/Products.vue'),
        meta: { title: '商品与规格变体', requiresMerchant: true }
      },
      {
        path: '/merchant/cards',
        name: 'MerchantCards',
        component: () => import('../views/merchant/Cards.vue'),
        meta: { title: '16688 虚拟卡密库', requiresMerchant: true }
      },
      {
        path: '/merchant/orders',
        name: 'MerchantOrders',
        component: () => import('../views/merchant/Orders.vue'),
        meta: { title: '本店订单与履约', requiresMerchant: true }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes
})

router.beforeEach(createSessionGuard({
  storage: localStorage,
  loadProfile: async () => (await request.get('/auth/me', { skipAuthRedirect: true, skipErrorToast: true })).data
}))

export default router
