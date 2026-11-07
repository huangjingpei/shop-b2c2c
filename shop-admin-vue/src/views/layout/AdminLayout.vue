<!--
文件：AdminLayout.vue
用途：管理端布局、角色视角切换与菜单框架。
维护注意：本地角色与视角只控制展示，不授予店铺操作权；未绑定店铺的超管不能默认写店铺1。退出须等服务端确认，网络失败不假装退出；页面身份由路由服务端校验后同步。
退出跳转按登录时的角色回对应入口：超管回 /admin/login，商家回 /login，不把商家带到私密入口。
会话隔离：展示身份读取“实际 token 所在桶”（商家页缺商家桶时回落超管桶，保住超管查看商家工作台）；退出只注销并清理该桶，另一桶登录状态无感。
-->
<template>
  <el-container class="layout-container">
    <!-- 顶部导航栏 -->
    <el-header class="layout-header">
      <div class="logo-area">
        <span class="logo-icon">🛍️</span>
        <span class="logo-title">B2B2C 统一管理系统</span>
        <el-tag :type="roleCode === 'SUPERADMIN' ? 'danger' : 'success'" effect="dark" size="small" class="role-tag">
          {{ roleCode === 'SUPERADMIN' ? '平台超管' : '入驻商家' }}
        </el-tag>
      </div>

      <!-- 双重视角切换控件 -->
      <div class="perspective-switcher" v-if="roleCode === 'SUPERADMIN'">
        <span class="switcher-label">当前视角：</span>
        <el-radio-group v-model="perspective" size="default" @change="handlePerspectiveChange">
          <el-radio-button label="ADMIN">
            <el-icon><Platform /></el-icon> 平台超管全局视角
          </el-radio-button>
          <el-radio-button label="MERCHANT" :disabled="!merchantId" title="商家视角需要绑定店铺">
            <el-icon><Shop /></el-icon> 商家工作台视角
          </el-radio-button>
        </el-radio-group>
      </div>
      <div class="merchant-badge" v-else>
        <el-tag type="primary" effect="plain">当前店铺：{{ storeName }}</el-tag>
      </div>

      <!-- 用户信息与退出 -->
      <div class="user-area">
        <el-dropdown @command="handleCommand">
          <span class="el-dropdown-link">
            <el-avatar :size="30" icon="UserFilled" style="background-color: #2563eb;" />
            <span class="username">{{ realName }}</span>
            <el-icon class="el-icon--right"><arrow-down /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout" :disabled="loggingOut">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </el-header>

    <el-container>
      <!-- 左侧动态侧边栏 -->
      <el-aside width="240px" class="layout-aside">
        <el-menu
          :default-active="route.path"
          router
          class="sidebar-menu"
          background-color="#1e293b"
          text-color="#94a3b8"
          active-text-color="#38bdf8"
        >
          <!-- 平台超管菜单 -->
          <template v-if="perspective === 'ADMIN'">
            <div class="menu-group-title">平台治理中心</div>
            <el-menu-item index="/admin/dashboard">
              <el-icon><DataLine /></el-icon>
              <span>平台交易大盘</span>
            </el-menu-item>
            <el-menu-item index="/admin/merchants">
              <el-icon><OfficeBuilding /></el-icon>
              <span>入驻商户治理与进件</span>
            </el-menu-item>
            <el-menu-item index="/admin/onboarding">
              <el-icon><DocumentChecked /></el-icon>
              <span>开店申请审核</span>
            </el-menu-item>
            <el-menu-item index="/admin/categories">
              <el-icon><Folder /></el-icon>
              <span>统一商品类目树</span>
            </el-menu-item>
            <el-menu-item index="/admin/product-moderation">
              <el-icon><Warning /></el-icon>
              <span>商品违规处置与审核</span>
            </el-menu-item>
            <el-menu-item index="/admin/orders">
              <el-icon><Tickets /></el-icon>
              <span>全平台订单与分账监察</span>
            </el-menu-item>
            <el-menu-item index="/admin/finance">
              <el-icon><Wallet /></el-icon>
              <span>财务复核与结算</span>
            </el-menu-item>
            <el-menu-item index="/admin/billing">
              <el-icon><Money /></el-icon>
              <span>商户账单治理</span>
            </el-menu-item>
            <!-- F10：SaaS 月费账单与交易抽佣分开，仅超管视角可见；后端二次校验 SUPERADMIN。 -->
            <el-menu-item index="/admin/saas-fee">
              <el-icon><Coin /></el-icon>
              <span>SaaS 月费账单</span>
            </el-menu-item>
            <!-- F06：平台投诉仲裁，仅超管视角可见；后端 /api/v1/admin/complaints 二次校验。 -->
            <el-menu-item index="/admin/complaints">
              <el-icon><ChatDotRound /></el-icon>
              <span>投诉仲裁</span>
            </el-menu-item>
          </template>

          <!-- 商家工作台菜单 -->
          <template v-else>
            <div class="menu-group-title">商家日常运营</div>
            <el-menu-item index="/merchant/dashboard">
              <el-icon><Odometer /></el-icon>
              <span>店铺经营看板</span>
            </el-menu-item>
            <el-menu-item index="/merchant/profile">
              <el-icon><Shop /></el-icon>
              <span>店铺资料与分账卡</span>
            </el-menu-item>
            <el-menu-item index="/merchant/products">
              <el-icon><Goods /></el-icon>
              <span>商品发布与多规格变体</span>
            </el-menu-item>
            <el-menu-item v-if="roleCode !== 'MERCHANT_STAFF'" index="/merchant/cards">
              <el-icon><CreditCard /></el-icon>
              <span>16688 虚拟卡密库</span>
            </el-menu-item>
            <el-menu-item index="/merchant/orders">
              <el-icon><List /></el-icon>
              <span>订单履约与发货</span>
            </el-menu-item>
            <!-- F05：退款申请审批仅店长可见；授权由后端接口强制。 -->
            <el-menu-item v-if="roleCode !== 'MERCHANT_STAFF'" index="/merchant/refund-requests">
              <el-icon><Refund /></el-icon>
              <span>退款申请审批</span>
            </el-menu-item>
            <!-- F09：账单与卡密库同口径，仅店长可见；员工直接输入地址也会被路由守卫与后端双重拒绝。 -->
            <el-menu-item v-if="roleCode !== 'MERCHANT_STAFF'" index="/merchant/billing">
              <el-icon><Money /></el-icon>
              <span>本店账单</span>
            </el-menu-item>
            <!-- F10：SaaS 月费账单只读，仅店长可见；员工直接输入地址也会被路由守卫与后端拒绝。 -->
            <el-menu-item v-if="roleCode !== 'MERCHANT_STAFF'" index="/merchant/saas-fee">
              <el-icon><Coin /></el-icon>
              <span>SaaS 月费账单</span>
            </el-menu-item>
            <!-- F06：投诉答复与举证仅店长可见；商家只能处理本店工单。 -->
            <el-menu-item v-if="roleCode !== 'MERCHANT_STAFF'" index="/merchant/complaints">
              <el-icon><ChatDotRound /></el-icon>
              <span>投诉处理</span>
            </el-menu-item>
          </template>
        </el-menu>
      </el-aside>

      <!-- 主内容区域 -->
      <el-main class="layout-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import request from '../../api/request'
import { loginPathFor, logoutSession, pickToken, sessionGet, sessionSet } from '../../utils/authSession.mjs'
import { useRouter, useRoute } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'

const router = useRouter()
const route = useRoute()

// 目标视角由路由前缀决定；展示身份读取实际 token 所在桶（商家页缺商家桶时回落超管桶）。
const routePerspective = () => route.path.startsWith('/admin/') ? 'ADMIN' : 'MERCHANT'
const currentBucket = () => pickToken(localStorage, routePerspective()).perspective

const bucket = currentBucket()
const roleCode = ref(sessionGet(localStorage, 'roleCode', bucket) || '')
const realName = ref(sessionGet(localStorage, 'realName', bucket) || '管理员')
const storeName = ref(sessionGet(localStorage, 'storeName', bucket) || '平台总店')
const merchantId = ref(sessionGet(localStorage, 'merchantId', bucket) || '')
const loggingOut = ref(false)
const perspective = ref(roleCode.value === 'SUPERADMIN' ? (sessionGet(localStorage, 'currentPerspective', 'ADMIN') || 'ADMIN') : 'MERCHANT')

const handlePerspectiveChange = (val) => {
  // 视角偏好属于超管桶；商家视角的接口请求仍按路由自动回落超管 token。
  sessionSet(localStorage, 'currentPerspective', val, 'ADMIN')
  if (val === 'ADMIN') {
    router.push('/admin/dashboard')
    ElMessage.info('已切换至【平台超级管理员】全局治理视角')
  } else {
    router.push('/merchant/dashboard')
    ElMessage.info('已切换至【商家工作台】视角')
  }
}

const handleCommand = async (cmd) => {
  if (cmd !== 'logout' || loggingOut.value) return
  try { await ElMessageBox.confirm('确定要退出当前管理控制台吗？', '提示', { type: 'warning' }) }
  catch { return }
  loggingOut.value = true
  try {
    const cleared = await logoutSession({
      storage: localStorage,
      perspective: routePerspective(),
      postLogout: () => request.post('/auth/logout', undefined, { skipErrorToast: true, skipAuthRedirect: true })
    })
    if (cleared) {
      const loginTarget = loginPathFor(import.meta.env.BASE_URL, roleCode.value === 'SUPERADMIN' ? 'ADMIN' : 'MERCHANT')
      await router.replace(loginTarget)
      ElMessage.success('已退出登录')
    }
  } catch {
    ElMessage.error('退出未完成，请检查网络后重试')
  } finally { loggingOut.value = false }
}

const syncDisplayProfile = () => {
  const bucket = currentBucket()
  roleCode.value = sessionGet(localStorage, 'roleCode', bucket) || ''
  realName.value = sessionGet(localStorage, 'realName', bucket) || '用户'
  storeName.value = sessionGet(localStorage, 'storeName', bucket) || '平台总部'
  merchantId.value = sessionGet(localStorage, 'merchantId', bucket) || ''
  if (roleCode.value !== 'SUPERADMIN') perspective.value = 'MERCHANT'
  else perspective.value = route.path.startsWith('/admin/') ? 'ADMIN' : 'MERCHANT'
}
onMounted(syncDisplayProfile)
watch(() => route.fullPath, syncDisplayProfile)
</script>

<style scoped>
.layout-container {
  height: 100vh;
}

.layout-header {
  height: 60px;
  background-color: #0f172a;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  border-bottom: 1px solid #334155;
}

.logo-area {
  display: flex;
  align-items: center;
  gap: 12px;
}

.logo-icon {
  font-size: 24px;
}

.logo-title {
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 0.5px;
}

.role-tag {
  margin-left: 8px;
}

.perspective-switcher {
  display: flex;
  align-items: center;
}

.switcher-label {
  font-size: 13px;
  color: #94a3b8;
  margin-right: 8px;
}

.user-area {
  display: flex;
  align-items: center;
}

.el-dropdown-link {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #e2e8f0;
  cursor: pointer;
}

.username {
  font-size: 14px;
}

.layout-aside {
  background-color: #1e293b;
}

.sidebar-menu {
  border-right: none;
}

.menu-group-title {
  padding: 16px 20px 8px;
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  color: #64748b;
  letter-spacing: 1px;
}

.layout-main {
  background-color: #f8fafc;
  padding: 24px;
  overflow-y: auto;
}
</style>
