/*
 * 文件：authSession.test.mjs
 * 用途：管理端会话撤销、角色路由、跨标签页和HTTP认证错误规则的隔离单元测试。
 * 维护注意：使用内存storage和可控Promise，不访问浏览器或服务端；路由守卫不能代替后端鉴权。错误响应既覆盖HTTP状态也覆盖R.code。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { clearSession, loginPath, homeFor, canEnter, createSessionGuard, logoutSession,
  createApiResponseHandlers, storageNavigation } from '../shop-admin-vue/src/utils/authSession.mjs'

function storage(seed = { token: 'old', roleCode: 'SUPERADMIN', unrelated: 'keep' }) {
  const data = new Map(Object.entries(seed))
  return { getItem: key => data.get(key) ?? null, setItem: (key, value) => data.set(key, String(value)), removeItem: key => data.delete(key) }
}
const merchant = { id: 2, roleCode: 'MERCHANT_ADMIN', merchantId: 1 }
const adminRoute = { path: '/admin/dashboard', meta: { role: 'SUPERADMIN' } }
const merchantRoute = { path: '/merchant/dashboard', meta: { requiresMerchant: true } }

test('清理仅移除应用会话键，登录地址遵循部署base', () => {
  const s = storage(); clearSession(s)
  assert.equal(s.getItem('token'), null); assert.equal(s.getItem('roleCode'), null)
  assert.equal(s.getItem('unrelated'), 'keep')
  for (const [base, expected] of [['/', '/login'], ['/admin/', '/admin/login'], ['/console/', '/console/login']]) assert.equal(loginPath(base), expected)
})
test('角色主页与未声明授权策略默认拒绝', () => {
  assert.equal(homeFor(merchant), '/merchant/dashboard')
  assert.equal(homeFor({ roleCode: 'SUPERADMIN' }), '/admin/dashboard')
  assert.equal(homeFor({ ...merchant, merchantId: null }), '/forbidden')
  assert.equal(canEnter(merchant, adminRoute), false)
  assert.equal(canEnter(merchant, merchantRoute), true)
  assert.equal(canEnter({ ...merchant, roleCode: 'MERCHANT_STAFF' }, merchantRoute), true)
  assert.equal(canEnter({ ...merchant, roleCode: 'UNKNOWN' }, merchantRoute), false)
  assert.equal(canEnter({ ...merchant, merchantId: null }, merchantRoute), false)
  assert.equal(canEnter(merchant, { path: '/future', meta: {} }), false)
  // Q13-A：卡密库路由声明 role=MERCHANT_ADMIN，员工即使直接输入地址也不得进入。
  const cardRoute = { path: '/merchant/cards', meta: { requiresMerchant: true, role: 'MERCHANT_ADMIN' } }
  assert.equal(canEnter(merchant, cardRoute), true)
  assert.equal(canEnter({ ...merchant, roleCode: 'MERCHANT_STAFF' }, cardRoute), false)
})
test('守卫采用服务端角色覆盖伪造缓存，拒绝商家平台路由', async () => {
  const s = storage()
  const guard = createSessionGuard({ storage: s, loadProfile: async () => merchant })
  assert.equal(await guard(adminRoute), '/forbidden')
  assert.equal(s.getItem('roleCode'), 'MERCHANT_ADMIN')
  assert.equal(await guard(merchantRoute), true)
  assert.equal(await guard({ path: '/' }), '/merchant/dashboard')
})
test('无令牌及公开错误页面不请求个人资料', async () => {
  const guard = createSessionGuard({ storage: storage({}), loadProfile: () => assert.fail('must not fetch') })
  assert.equal(await guard(adminRoute), '/login')
  for (const path of ['/login', '/forbidden', '/missing', '/session-error']) assert.equal(await guard({ path, meta: { public: true } }), true)
})
test('认证失效清理本地状态，网络失败保留令牌进入验证故障页', async () => {
  for (const error of [{ response: { status: 401 } }, { businessCode: 401 }, new Error('offline')]) {
    const s = storage()
    const result = await createSessionGuard({ storage: s, loadProfile: async () => { throw error } })(adminRoute)
    assert.equal(result, error.message === 'offline' ? '/session-error' : '/login')
    assert.equal(s.getItem('token'), error.message === 'offline' ? 'old' : null)
  }
})
test('旧资料响应与401不能覆盖或清除新登录', async () => {
  for (const failed of [false, true]) {
    const s = storage(); let finish
    const pending = new Promise((resolve, reject) => { finish = () => failed ? reject({ businessCode: 401 }) : resolve(merchant) })
    const result = createSessionGuard({ storage: s, loadProfile: () => pending })(adminRoute)
    s.setItem('token', 'new'); finish()
    assert.equal(await result, '/'); assert.equal(s.getItem('token'), 'new')
    assert.equal(s.getItem('roleCode'), 'SUPERADMIN')
  }
})
test('退出先完成服务端撤销再清理；过期令牌视为已退出', async () => {
  for (const expired of [false, true]) {
    const s = storage()
    assert.equal(await logoutSession({ storage: s, postLogout: async () => {
      assert.equal(s.getItem('token'), 'old')
      if (expired) throw { response: { status: 401 } }
      return { code: 200 }
    } }), true)
    assert.equal(s.getItem('token'), null); assert.equal(s.getItem('unrelated'), 'keep')
  }
})
test('退出失败保留会话且不能清理并发新登录', async () => {
  for (const response of [null, { code: 500 }]) {
    const s = storage()
    await assert.rejects(logoutSession({ storage: s, postLogout: async () => { if (!response) throw new Error('offline'); return response } }))
    assert.equal(s.getItem('token'), 'old')
  }
  const s = storage()
  assert.equal(await logoutSession({ storage: s, postLogout: async () => { s.setItem('token', 'new'); return { code: 200 } } }), false)
  assert.equal(s.getItem('token'), 'new')
})
test('HTTP401和业务401使用部署base跳转，其余失败不清理登录', () => {
  for (const code of [401, 403, 500]) for (const http of [false, true]) {
    const s = storage(); const notices = [], redirects = []
    const handlers = createApiResponseHandlers({ storage: s, base: '/console/', notify: m => notices.push(m), redirect: p => redirects.push(p) })
    const config = { sessionToken: 'old' }
    assert.throws(() => http ? handlers.rejected({ config, response: { status: code } }) : handlers.fulfilled({ config, data: { code } }))
    assert.equal(s.getItem('token'), code === 401 ? null : 'old')
    assert.deepEqual(redirects, code === 401 ? ['/console/login'] : [])
    assert.equal(notices.length, 1)
  }
})
test('API旧请求401不干扰新登录，守卫专用请求可关闭跳转和提示', () => {
  for (const stale of [false, true]) {
    const s = storage(); const notices = [], redirects = []
    const handlers = createApiResponseHandlers({ storage: s, base: '/', notify: m => notices.push(m), redirect: p => redirects.push(p) })
    assert.throws(() => handlers.fulfilled({ config: { sessionToken: stale ? 'older' : 'old', skipErrorToast: !stale, skipAuthRedirect: !stale }, data: { code: 401 } }))
    assert.equal(s.getItem('token'), stale ? 'old' : null)
    assert.deepEqual(redirects, []); assert.deepEqual(notices, [])
    assert.deepEqual(handlers.fulfilled({ data: { code: 200, data: 1 } }), { code: 200, data: 1 })
  }
})
test('跨标签页只对token变化触发重新导航', () => {
  assert.equal(storageNavigation({ key: 'token', newValue: null }), '/login')
  assert.equal(storageNavigation({ key: 'token', newValue: 'new' }), '/')
  assert.equal(storageNavigation({ key: 'unrelated' }), null)
})
