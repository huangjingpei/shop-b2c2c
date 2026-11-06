/*
 * 文件：authSession.mjs
 * 用途：管理端会话清理、退出确认、服务端身份路由判断与API认证错误处理。
 * 维护注意：本地角色仅供展示，不作为授权；网络失败不能假装服务端退出成功。仅移除本应用会话键，旧请求不得清除新登录；前端判断不替代后端角色及租户校验。
 * 双入口：/login 为商家登录（含“我要开店”），/admin/login 为平台超管私密入口；loginPathFor 按视角选择失效跳转，避免商家被踢到超管入口或反之。
 * 会话隔离：超管与商家会话分桶存放——商家桶沿用原始键名（token/userId/...），超管桶统一加 admin: 前缀；登录只写本桶、登出只清本桶，同一浏览器两个后台互不干扰。
 * 读桶规则：按路由/接口前缀确定目标视角，桶内缺 token 时回落另一桶（保住超管无商家会话仍可查看商家工作台的既有语义）；旧版本无前缀超管会话由 migrateLegacySession 在启动时迁入 admin: 桶。
 * D11：登录/会话返回 mustChangePassword 时，守卫把标记账号限制在 /change-password（带 ?p=视角 查询，改密后按该视角清理并回对应入口）；不得用本地缓存标记放行业务页。
 */
export const PERSPECTIVE_ADMIN = 'ADMIN'
export const PERSPECTIVE_MERCHANT = 'MERCHANT'
const ADMIN_PREFIX = 'admin:'
const sessionKeys = ['token', 'userId', 'roleCode', 'merchantId', 'realName', 'storeName', 'currentPerspective', 'mustChangePassword']
/** D11 强制改密页地址；该路由不声明角色，任何已登录账号均可进入改自己的密码。 */
export const CHANGE_PASSWORD_PATH = '/change-password'
const roles = new Set(['SUPERADMIN', 'MERCHANT_ADMIN', 'MERCHANT_STAFF'])

function normalizePerspective(value) {
  const upper = String(value || '').toUpperCase()
  return upper === PERSPECTIVE_ADMIN ? PERSPECTIVE_ADMIN : (upper === PERSPECTIVE_MERCHANT ? PERSPECTIVE_MERCHANT : '')
}
/** 视角分桶键：超管桶加 admin: 前缀，商家桶沿用原始键名（已登录商家零感知）。 */
export function bucketKey(key, perspective) {
  return normalizePerspective(perspective) === PERSPECTIVE_ADMIN ? ADMIN_PREFIX + key : key
}
function currentPath() {
  try { return globalThis.location?.pathname || '' } catch { return '' }
}
/**
 * 判定当前视角：显式参数 > 页面路径（/admin/**）> 桶内唯一 token > 商家桶旧缓存 > 默认商家。
 * 两桶都有 token 且路径无前缀时归商家桶；已知路由/接口的调用方应优先传显式视角。
 */
export function resolvePerspective(storage, explicit) {
  const wanted = normalizePerspective(explicit)
  if (wanted) return wanted
  if (/^\/admin(\/|$)/.test(currentPath())) return PERSPECTIVE_ADMIN
  const adminToken = storage.getItem(bucketKey('token', PERSPECTIVE_ADMIN))
  const merchantToken = storage.getItem(bucketKey('token', PERSPECTIVE_MERCHANT))
  if (adminToken && !merchantToken) return PERSPECTIVE_ADMIN
  if (merchantToken && !adminToken) return PERSPECTIVE_MERCHANT
  return normalizePerspective(storage.getItem('currentPerspective')) || PERSPECTIVE_MERCHANT
}
/** 按路由路径判定目标视角；公开或无关路径返回空串，由调用方回落到 resolvePerspective。 */
export function perspectiveForRoute(route) {
  const path = route?.path || ''
  if (/^\/admin(\/|$)/.test(path)) return PERSPECTIVE_ADMIN
  if (/^\/merchant(\/|$)/.test(path)) return PERSPECTIVE_MERCHANT
  return ''
}
/** 读本桶键；视角缺失时按 resolvePerspective 判定。需要“缺桶回落”的场景请用 pickToken/sessionBucket。 */
export function sessionGet(storage, key, perspective) {
  return storage.getItem(bucketKey(key, resolvePerspective(storage, perspective)))
}
export function sessionSet(storage, key, value, perspective) {
  storage.setItem(bucketKey(key, resolvePerspective(storage, perspective)), value)
}
/**
 * 取目标视角的 token：本桶优先，缺桶回落另一桶（超管无商家会话仍可查看商家工作台）。
 * 返回 { token, perspective }，perspective 是 token 实际所在桶；401 清理与退出注销必须按该桶执行。
 */
export function pickToken(storage, perspective) {
  const wanted = normalizePerspective(perspective) || PERSPECTIVE_MERCHANT
  const fallback = wanted === PERSPECTIVE_ADMIN ? PERSPECTIVE_MERCHANT : PERSPECTIVE_ADMIN
  const own = storage.getItem(bucketKey('token', wanted))
  if (own) return { token: own, perspective: wanted }
  const other = storage.getItem(bucketKey('token', fallback))
  return other ? { token: other, perspective: fallback } : { token: null, perspective: wanted }
}
/** 读展示用字段时确定实际桶（等价 pickToken(...).perspective），配合 sessionGet 使用。 */
export function sessionBucket(storage, perspective) {
  return pickToken(storage, perspective).perspective
}
/** 清理会话：指定视角只清对应桶；不传视角清两桶（兼容整体重置）。 */
export function clearSession(storage, perspective) {
  const wanted = normalizePerspective(perspective)
  const buckets = wanted ? [wanted] : [PERSPECTIVE_MERCHANT, PERSPECTIVE_ADMIN]
  for (const bucket of buckets) sessionKeys.forEach(key => storage.removeItem(bucketKey(key, bucket)))
}
/** 旧版本超管会话（无前缀键 + currentPerspective=ADMIN）启动时迁入 admin: 桶，避免与商家桶互相覆盖。 */
export function migrateLegacySession(storage) {
  if (!storage.getItem('token')) return
  if (normalizePerspective(storage.getItem('currentPerspective')) !== PERSPECTIVE_ADMIN) return
  if (storage.getItem(bucketKey('token', PERSPECTIVE_ADMIN))) {
    // admin: 桶已有新会话时，丢弃残留的旧无前缀超管会话，防重复覆盖。
    sessionKeys.forEach(key => storage.removeItem(key))
    return
  }
  sessionKeys.forEach(key => {
    const value = storage.getItem(key)
    if (value !== null && value !== undefined) storage.setItem(bucketKey(key, PERSPECTIVE_ADMIN), value)
    storage.removeItem(key)
  })
}
export function loginPath(base = '/') {
  const prefix = '/' + String(base).split('/').filter(Boolean).join('/')
  return prefix === '/' ? '/login' : prefix + '/login'
}
/** 按视角返回登录页：ADMIN 走私密入口，其余（含未知）走商家入口。 */
export function loginPathFor(base = '/', perspective = '') {
  const path = loginPath(base)
  return normalizePerspective(perspective) === PERSPECTIVE_ADMIN ? path.replace(/\/login$/, '/admin/login') : path
}
export function apiErrorCode(error) {
  const body = Number(error?.businessCode ?? error?.response?.data?.code)
  const status = Number(error?.response?.status)
  return body === 401 || status === 401 ? 401 : (body || status || 0)
}
export function cacheProfile(storage, profile, perspective) {
  const bucket = resolvePerspective(storage, perspective)
  sessionSet(storage, 'roleCode', profile.roleCode || '', bucket)
  sessionSet(storage, 'userId', String(profile.id ?? profile.userId ?? ''), bucket)
  sessionSet(storage, 'merchantId', String(profile.merchantId ?? ''), bucket)
  sessionSet(storage, 'realName', profile.realName || profile.username || '', bucket)
  sessionSet(storage, 'storeName', profile.store?.storeName || profile.storeName || '', bucket)
  // D11：仅缓存展示用标记；后端 428 才是强制拦截，本地缺失不视为已解除。
  sessionSet(storage, 'mustChangePassword', profile.mustChangePassword ? '1' : '', bucket)
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
    // 目标视角由路由决定；token 可能回落自另一桶，资料缓存与后续清理以实际桶为准。
    const perspective = perspectiveForRoute(route) || resolvePerspective(storage)
    const picked = pickToken(storage, perspective)
    const token = picked.token
    const current = () => pickToken(storage, perspective).token
    if (!token) return fallbackLogin(perspective)
    try {
      const profile = await loadProfile(picked.perspective)
      if (current() !== token) return current() ? '/' : fallbackLogin(perspective)
      cacheProfile(storage, profile, picked.perspective)
      // D11：标记账号只放行改密页（携带实际桶视角，改密后按桶清理）；已清除标记的账号访问改密页则送回主页。
      if (profile.mustChangePassword) {
        return route.path === CHANGE_PASSWORD_PATH ? true : `${CHANGE_PASSWORD_PATH}?p=${picked.perspective}`
      }
      if (route.path === '/') return homeFor(profile)
      if (route.path === CHANGE_PASSWORD_PATH) return homeFor(profile)
      return canEnter(profile, route) ? true : '/forbidden'
    } catch (error) {
      if (apiErrorCode(error) === 401) {
        const loginTarget = fallbackLogin(perspective)
        if (current() === token) clearSession(storage, picked.perspective)
        return current() ? '/' : loginTarget
      }
      if (current() !== token) return current() ? '/' : fallbackLogin(perspective)
      return '/session-error'
    }
  }
}
export async function logoutSession({ storage, postLogout, perspective }) {
  // 只注销并清理实际 token 所在桶；另一桶的登录状态保持无感。
  const picked = pickToken(storage, resolvePerspective(storage, perspective))
  const token = picked.token
  try {
    const response = await postLogout()
    if (response?.code !== 200) {
      const error = new Error('退出未完成'); error.businessCode = response?.code; throw error
    }
  } catch (error) {
    if (apiErrorCode(error) !== 401) throw error
  }
  const remaining = storage.getItem(bucketKey('token', picked.perspective))
  if (remaining && remaining !== token) return false
  clearSession(storage, picked.perspective)
  return true
}
export function createApiResponseHandlers({ storage, notify, redirect, base }) {
  function reject(error, config = {}) {
    const bucket = normalizePerspective(config.sessionPerspective) || resolvePerspective(storage)
    const ownsSession = (config.sessionToken ?? null) === storage.getItem(bucketKey('token', bucket))
    if (apiErrorCode(error) === 401 && ownsSession) {
      // 只清理实际发出 token 的桶并回对应入口；另一桶（含并发新登录）不受影响。
      clearSession(storage, bucket)
      if (!config.skipAuthRedirect) redirect(loginPathFor(base, bucket))
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
/**
 * 跨标签页同步：只响应当前视角桶的 token 变化——本桶新登录回 '/' 重新验证，本桶登出回对应登录页；
 * 另一桶的登录/登出与当前页面无关，返回 null 不导航（同机两个后台互不干扰）。
 */
export function storageNavigation(event, perspective = '') {
  const wanted = normalizePerspective(perspective)
  const buckets = wanted ? [wanted] : [PERSPECTIVE_MERCHANT, PERSPECTIVE_ADMIN]
  for (const bucket of buckets) {
    if (event.key === bucketKey('token', bucket)) return event.newValue ? '/' : loginPathFor('/', bucket)
  }
  return null
}
