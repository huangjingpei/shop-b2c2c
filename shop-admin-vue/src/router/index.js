/*
 * 文件：index.js
 * 用途：页面路由及导航策略配置。
 * 维护注意：history模式依赖生产代理回退与正确base；前端路由保护不替代API授权，每次受保护导航用/auth/me核验身份；403/404/验证故障页不渲染私有数据，本地角色不能作为授权。
 * 双登录入口：/login 商家登录（含开店向导），/admin/login 平台超管私密入口；会话失效按当前路由视角回到对应入口。
 * 会话隔离：启动时把旧版无前缀超管会话迁入 admin: 桶；守卫按路由前缀判定目标视角（/admin→超管桶、/merchant→商家桶），缺桶回落另一桶。
 * /apply 为公开开店向导，未登录可访问，不得把审核接口或商户私有数据挂到该页。
 */
import { createRouter, createWebHistory } from 'vue-router'
import AdminLayout from '../views/layout/AdminLayout.vue'
import Login from '../views/login/Login.vue'
import AdminLogin from '../views/login/AdminLogin.vue'
import request from '../api/request'
import { createSessionGuard, loginPathFor, migrateLegacySession } from '../utils/authSession.mjs'

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
    path: '/admin/login',
    name: 'AdminLogin',
    component: AdminLogin,
    meta: { public: true }
  },
  {
    path: '/apply',
    name: 'Apply',
    component: () => import('../views/onboarding/ApplyWizard.vue'),
    meta: { public: true }
  },
  {
    // D11：强制改密页需要登录令牌但不声明角色，守卫把标记账号限制在本页。
    path: '/change-password',
    name: 'ChangePassword',
    component: () => import('../views/login/ChangePassword.vue'),
    meta: { title: '首次登录修改密码' }
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
        path: '/admin/onboarding',
        name: 'AdminOnboarding',
        component: () => import('../views/admin/Onboarding.vue'),
        meta: { title: '开店申请审核', role: 'SUPERADMIN' }
      },
      {
        path: '/admin/categories',
        name: 'AdminCategories',
        component: () => import('../views/admin/Categories.vue'),
        meta: { title: '统一商品类目', role: 'SUPERADMIN' }
      },
      {
        // F11：平台商品违规处置与审核恢复；页面入口不替代后端 SUPERADMIN 校验。
        path: '/admin/product-moderation',
        name: 'AdminProductModeration',
        component: () => import('../views/admin/ProductModeration.vue'),
        meta: { title: '商品违规处置', role: 'SUPERADMIN' }
      },
      {
        path: '/admin/orders',
        name: 'AdminOrders',
        component: () => import('../views/admin/Orders.vue'),
        meta: { title: '全平台交易监察', role: 'SUPERADMIN' }
      },
      {
        path: '/admin/finance',
        name: 'AdminFinance',
        component: () => import('../views/admin/Finance.vue'),
        // G31：财务复核人入口仅超管可见；页面内的复核人选择不改变后端角色校验。
        meta: { title: '财务复核与结算', role: 'SUPERADMIN' }
      },
      {
        path: '/admin/billing',
        name: 'AdminBilling',
        component: () => import('../views/admin/Billing.vue'),
        // F09：全平台商户账单查询与重算入口，仅超管；重算为聚合覆盖，不触碰结算批次锁定语义。
        meta: { title: '商户账单治理', role: 'SUPERADMIN' }
      },
      {
        path: '/admin/saas-fee',
        name: 'AdminSaasFee',
        component: () => import('../views/admin/SaasFee.vue'),
        // F10：SaaS 月费账单治理，仅超管；与交易抽佣分开，缴费确认需线下凭证。
        meta: { title: 'SaaS 月费账单治理', role: 'SUPERADMIN' }
      },
      {
        path: '/admin/complaints',
        name: 'AdminComplaints',
        component: () => import('../views/admin/Complaints.vue'),
        // F06：平台仲裁仅超管；裁决可联动退款，退款上限与状态机由后端强制。
        meta: { title: '投诉仲裁', role: 'SUPERADMIN' }
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
        // Q13-A：卡密库仅店长可达；员工即使直接输入地址也会被路由守卫与后端接口双重拒绝。
        meta: { title: '16688 虚拟卡密库', requiresMerchant: true, role: 'MERCHANT_ADMIN' }
      },
      {
        path: '/merchant/orders',
        name: 'MerchantOrders',
        component: () => import('../views/merchant/Orders.vue'),
        meta: { title: '本店订单与履约', requiresMerchant: true }
      },
      {
        path: '/merchant/refund-requests',
        name: 'MerchantRefundRequests',
        component: () => import('../views/merchant/RefundRequests.vue'),
        meta: { title: '退款申请审批', requiresMerchant: true, role: 'MERCHANT_ADMIN' }
      },
      {
        path: '/merchant/billing',
        name: 'MerchantBilling',
        component: () => import('../views/merchant/Billing.vue'),
        // F09：账单属财务展示数据，仅店长可见（与卡密库同口径，路由守卫与后端接口双重校验）。
        meta: { title: '本店账单', requiresMerchant: true, role: 'MERCHANT_ADMIN' }
      },
      {
        path: '/merchant/saas-fee',
        name: 'MerchantSaasFee',
        component: () => import('../views/merchant/SaasFee.vue'),
        // F10：SaaS 月费账单只读，仅店长可见；缴费为平台线下人工确认。
        meta: { title: 'SaaS 月费账单', requiresMerchant: true, role: 'MERCHANT_ADMIN' }
      },
      {
        path: '/merchant/complaints',
        name: 'MerchantComplaints',
        component: () => import('../views/merchant/Complaints.vue'),
        // F06：投诉答复与举证涉及店铺对外口径，仅店长可见；商家只能处理本店工单。
        meta: { title: '投诉处理', requiresMerchant: true, role: 'MERCHANT_ADMIN' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes
})

// 旧版本超管会话（无前缀键）迁入 admin: 桶；商家会话键名不变，已登录商家零感知。
migrateLegacySession(localStorage)

router.beforeEach(createSessionGuard({
  storage: localStorage,
  loginPath: perspective => loginPathFor(import.meta.env.BASE_URL, perspective),
  loadProfile: async perspective => (await request.get('/auth/me', { perspective, skipAuthRedirect: true, skipErrorToast: true })).data
}))

export default router
