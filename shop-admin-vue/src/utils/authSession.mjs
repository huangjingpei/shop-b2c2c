/*
 * 文件：authSession.mjs
 * 用途：管理端会话清理、退出确认、服务端身份路由判断与API认证错误处理。
 * 维护注意：本地角色仅供展示，不作为授权；网络失败不能假装服务端退出成功。仅移除本应用会话键，旧请求不得清除新登录；前端判断不替代后端角色及租户校验。
 * 双入口：/login 为商家登录（含“我要开店”），/admin/login 为平台超管私密入口；loginPathFor 按 currentPerspective 选择失效跳转，避免商家被踢到超管入口或反之。
 * D11：登录/会话返回 mustChangePassword 时，守卫把标记账号限制在 /change-password，改密成功重新登录后标记清除；不得用本地缓存标记放行业务页。
 */
const sessionKeys = ['token', 'userId', 'roleCode', 'merchantId', 'realName', 'storeName', 'currentPerspective', 'mustChangePassword']
/** D11 强制改密页地址；该路由不声明角色，任何已登录账号均可进入改自己的密码。 */
export const CHANGE_PASSWORD_PATH = '/change-password'
const roles = new Set(['SUPERADMIN', 'MERCHANT_ADMIN', 'MERCHANT_STAFF'])
export function clearSession(storage) {
  sessionKeys.forEach(key => storage.removeItem(key))
}
export function loginPath(base = '/') {
  const prefix = '/' + String(base).split('/').filter(Boolean).join('/')
  return prefix === '/' ? '/login' : prefix + '/login'
}
/** 按视角返回登录页：ADMIN 走私密入口，其余（含未知）走商家入口。 */
export function loginPathFor(base = '/', perspective = '') {
  const path = loginPath(base)
  return String(perspective).toUpperCase() === 'ADMIN' ? path.replace(/\/login$/, '/admin/login') : path
}
export function apiErrorCode(error) {
  const body = Number(error?.businessCode ?? error?.response?.data?.code)
  const status = Number(error?.response?.status)
  return body === 401 || status === 401 ? 401 : (body || status || 0)
}
export function cacheProfile(storage, profile) {
  storage.setItem('roleCode', profile.roleCode || '')
  storage.setItem('userId', String(profile.id ?? profile.userId ?? ''))
  storage.setItem('merchantId', String(profile.merchantId ?? ''))
  storage.setItem('realName', profile.realName || profile.username || '')
  storage.setItem('storeName', profile.store?.storeName || profile.storeName || '')
  // D11：仅缓存展示用标记；后端 428 才是强制拦截，本地缺失不视为已解除。
  storage.setItem('mustChangePassword', profile.mustChangePassword ? '1' : '')
}
export function homeFor(profile) {
  if (profile?.roleCode === 'SUPERADMIN') return '/admin/dashboard'
  return roles.has(profile?.roleCode) && Number(profile.merchantId) > 0 ? '/merchant/dashboard' : '/forbidden'
}
export function canEnter(profile, route) {
  if (!roles.has(profile?.roleCode)) return false
  if (route.meta?.role && route.meta.role !== profile.roleCode) return false
  if (route.meta?.requiresMerchant && !(Number(profile.merchantId) > 0)) return false
  return Boolean(route.meta?.role || route.meta?.requiresMerchant)
}
export function createSessionGuard({ storage, loadProfile, loginPath: resolveLoginPath }) {
  const fallbackLogin = typeof resolveLoginPath === 'function' ? resolveLoginPath : () => '/login'
  return async route => {
    if (route.meta?.public) return true
    const token = storage.getItem('token')
    if (!token) return fallbackLogin()
    try {
      const profile = await loadProfile()
      if (storage.getItem('token') !== token) return storage.getItem('token') ? '/' : fallbackLogin()
      cacheProfile(storage, profile)
      // D11：标记账号只放行改密页；已清除标记的账号访问改密页则送回主页。
      if (profile.mustChangePassword) {
        return route.path === CHANGE_PASSWORD_PATH ? true : CHANGE_PASSWORD_PATH
      }
      if (route.path === '/') return homeFor(profile)
      if (route.path === CHANGE_PASSWORD_PATH) return homeFor(profile)
      return canEnter(profile, route) ? true : '/forbidden'
    } catch (error) {
      if (apiErrorCode(error) === 401) {
        const loginTarget = fallbackLogin()
        if (storage.getItem('token') === token) clearSession(storage)
        return storage.getItem('token') ? '/' : loginTarget
      }
      if (storage.getItem('token') !== token) return storage.getItem('token') ? '/' : fallbackLogin()
      return '/session-error'
    }
  }
}
export async function logoutSession({ storage, postLogout }) {
  const token = storage.getItem('token')
  try {
    const response = await postLogout()
    if (response?.code !== 200) {
      const error = new Error('退出未完成'); error.businessCode = response?.code; throw error
    }
  } catch (error) {
    if (apiErrorCode(error) !== 401) throw error
  }
  if (storage.getItem('token') && storage.getItem('token') !== token) return false
  clearSession(storage)
  return true
}
export function createApiResponseHandlers({ storage, notify, redirect, base }) {
  function reject(error, config = {}) {
    const ownsSession = (config.sessionToken ?? null) === storage.getItem('token')
    if (apiErrorCode(error) === 401 && ownsSession) {
      // 先按 currentPerspective 计算入口再清理会话，否则清理后只能回落到商家登录页。
      const loginTarget = loginPathFor(base, storage.getItem('currentPerspective'))
      clearSession(storage)
      if (!config.skipAuthRedirect) redirect(loginTarget)
    }
    if (!config.skipErrorToast && (apiErrorCode(error) !== 401 || ownsSession)) notify(error.response?.data?.message || error.message || '请求失败')
    throw error
  }
  return {
    fulfilled(response) {
      // 二进制下载（证件/认证视频预览）没有 R 业务包裹，直接返回 Blob，避免被当成失败响应。
      if (response.config?.responseType === 'blob') return response.data
      if (response.data?.code === 200) return response.data
      const error = new Error(response.data?.message || '请求失败')
      error.businessCode = response.data?.code; error.response = response
      return reject(error, response.config)
    },
    rejected(error) { return reject(error, error.config || error.response?.config) }
  }
}
export function storageNavigation(event, perspective = '') {
  return event.key === 'token' ? (event.newValue ? '/' : loginPathFor('/', perspective)) : null
}
