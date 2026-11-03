<!--
文件：AdminLayout.vue
用途：管理端布局、角色视角切换与菜单框架。
维护注意：本地角色与视角只控制展示，不授予店铺操作权；未绑定店铺的超管不能默认写店铺1。退出须等服务端确认，网络失败不假装退出；页面身份由路由服务端校验后同步。
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
            <el-menu-item index="/admin/categories">
              <el-icon><Folder /></el-icon>
              <span>统一商品类目树</span>
            </el-menu-item>
            <el-menu-item index="/admin/orders">
              <el-icon><Tickets /></el-icon>
              <span>全平台订单与分账监察</span>
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
            <el-menu-item index="/merchant/cards">
              <el-icon><CreditCard /></el-icon>
              <span>16688 虚拟卡密库</span>
            </el-menu-item>
            <el-menu-item index="/merchant/orders">
              <el-icon><List /></el-icon>
              <span>订单履约与发货</span>
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
import { logoutSession } from '../../utils/authSession.mjs'
import { useRouter, useRoute } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'

const router = useRouter()
const route = useRoute()

const roleCode = ref(localStorage.getItem('roleCode') || '')
const realName = ref(localStorage.getItem('realName') || '管理员')
const storeName = ref(localStorage.getItem('storeName') || '平台总店')
const merchantId = ref(localStorage.getItem('merchantId') || '')
const loggingOut = ref(false)
const perspective = ref(roleCode.value === 'SUPERADMIN' ? (localStorage.getItem('currentPerspective') || 'ADMIN') : 'MERCHANT')

const handlePerspectiveChange = (val) => {
  localStorage.setItem('currentPerspective', val)
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
      postLogout: () => request.post('/auth/logout', undefined, { skipErrorToast: true, skipAuthRedirect: true })
    })
    if (cleared) { await router.replace('/login'); ElMessage.success('已退出登录') }
  } catch {
    ElMessage.error('退出未完成，请检查网络后重试')
  } finally { loggingOut.value = false }
}

const syncDisplayProfile = () => {
  roleCode.value = localStorage.getItem('roleCode') || ''
  realName.value = localStorage.getItem('realName') || '用户'
  storeName.value = localStorage.getItem('storeName') || '平台总部'
  merchantId.value = localStorage.getItem('merchantId') || ''
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
