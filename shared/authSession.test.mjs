/*
 * 文件：authSession.test.mjs
 * 用途：管理端会话撤销、角色路由、跨标签页和HTTP认证错误规则的隔离单元测试。
 * 维护注意：使用内存storage和可控Promise，不访问浏览器或服务端；路由守卫不能代替后端鉴权。错误响应既覆盖HTTP状态也覆盖R.code。
 * 会话隔离：商家桶沿用无前缀原始键，超管桶统一 admin: 前缀；登录/登出/401清理只作用于目标桶，另一桶无感；
 * 目标桶缺 token 时守卫与请求回落另一桶（保住超管无商家会话仍可查看商家工作台）。
 * D11：强制改密标记账号只能进 /change-password（守卫重定向携带 ?p=视角 指明改密桶）；清除标记后访问改密页回主页；本地缓存标记不得放行业务页。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { clearSession, loginPath, loginPathFor, homeFor, canEnter, createSessionGuard, logoutSession,
  createApiResponseHandlers, storageNavigation, bucketKey, sessionGet, sessionSet, pickToken,
  resolvePerspective, migrateLegacySession, CHANGE_PASSWORD_PATH } from '../shop-admin-vue/src/utils/authSession.mjs'

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
test('跨标签页只对当前视角桶的token变化触发重新导航', () => {
  // 商家页：商家桶（无前缀）变化响应；超管桶（admin: 前缀）变化忽略。
  assert.equal(storageNavigation({ key: 'token', newValue: null }, 'MERCHANT'), '/login')
  assert.equal(storageNavigation({ key: 'token', newValue: 'new' }, 'MERCHANT'), '/')
  assert.equal(storageNavigation({ key: 'admin:token', newValue: null }, 'MERCHANT'), null)
  // 超管页：超管桶变化响应；商家桶变化忽略（两个后台互不干扰）。
  assert.equal(storageNavigation({ key: 'admin:token', newValue: null }, 'ADMIN'), '/admin/login')
  assert.equal(storageNavigation({ key: 'admin:token', newValue: 'new' }, 'ADMIN'), '/')
  assert.equal(storageNavigation({ key: 'token', newValue: null }, 'ADMIN'), null)
  assert.equal(storageNavigation({ key: 'unrelated' }, 'ADMIN'), null)
  // 未指定视角时兼容两桶（供无路由上下文场景）。
  assert.equal(storageNavigation({ key: 'token', newValue: null }), '/login')
  assert.equal(storageNavigation({ key: 'admin:token', newValue: null }), '/admin/login')
})
test('商家与超管双入口按视角选择登录页', () => {
  assert.equal(loginPathFor('/', 'ADMIN'), '/admin/login')
  assert.equal(loginPathFor('/console/', 'ADMIN'), '/console/admin/login')
  assert.equal(loginPathFor('/console/', 'MERCHANT'), '/console/login')
  assert.equal(loginPathFor('/console/'), '/console/login')
})
test('会话失效按视角回到对应入口', async () => {
  // 守卫半段必须无 token（验证未登录跳转）；处理器半段必须持有当前 token（验证 401 清理+重定向），两段不能共用 storage。
  const guard = createSessionGuard({
    storage: storage({}),
    loadProfile: () => assert.fail('must not fetch'),
    loginPath: perspective => loginPathFor('/console/', perspective)
  })
  assert.equal(await guard(adminRoute), '/console/admin/login')
  assert.equal(await guard(merchantRoute), '/console/login')

  const s2 = storage({ 'admin:token': 'admin-old', 'admin:currentPerspective': 'ADMIN' })
  const redirects = []
  const handlers = createApiResponseHandlers({
    storage: s2, base: '/console/', notify: () => {}, redirect: path => redirects.push(path)
  })
  assert.throws(() => handlers.fulfilled({ config: { sessionToken: 'admin-old', sessionPerspective: 'ADMIN' }, data: { code: 401 } }))
  assert.deepEqual(redirects, ['/console/admin/login'])
  assert.equal(s2.getItem('admin:token'), null)
})
test('Blob证件下载直接返回二进制，不按R业务码判失败', () => {
  const handlers = createApiResponseHandlers({
    storage: storage(), notify: () => assert.fail('must not notify'),
    redirect: () => assert.fail('must not redirect'), base: '/'
  })
  const blob = { type: 'image/png' }
  assert.equal(handlers.fulfilled({ config: { responseType: 'blob' }, data: blob }), blob)
})

// ---------- 会话隔离：超管桶（admin: 前缀）与商家桶（无前缀）互不干扰 ----------
test('分桶键映射与读写只作用于目标桶', () => {
  assert.equal(bucketKey('token', 'ADMIN'), 'admin:token')
  assert.equal(bucketKey('token', 'MERCHANT'), 'token')
  const s = storage({})
  sessionSet(s, 'token', 'merchant-tok', 'MERCHANT')
  sessionSet(s, 'token', 'admin-tok', 'ADMIN')
  assert.equal(sessionGet(s, 'token', 'MERCHANT'), 'merchant-tok')
  assert.equal(sessionGet(s, 'token', 'ADMIN'), 'admin-tok')
  // 登录超管不覆盖商家会话；反之亦然。
  assert.equal(s.getItem('token'), 'merchant-tok')
  assert.equal(s.getItem('admin:token'), 'admin-tok')
})
test('登录与登出互不影响：清理只作用于指定桶', async () => {
  const s = storage({ token: 'merchant-tok', roleCode: 'MERCHANT_ADMIN', 'admin:token': 'admin-tok', 'admin:roleCode': 'SUPERADMIN', unrelated: 'keep' })
  // 商家登出：只清商家桶，超管桶原样保留。
  assert.equal(await logoutSession({ storage: s, perspective: 'MERCHANT', postLogout: async () => ({ code: 200 }) }), true)
  assert.equal(s.getItem('token'), null)
  assert.equal(s.getItem('roleCode'), null)
  assert.equal(s.getItem('admin:token'), 'admin-tok')
  assert.equal(s.getItem('admin:roleCode'), 'SUPERADMIN')
  assert.equal(s.getItem('unrelated'), 'keep')
  // 超管登出：只清超管桶，商家桶原样保留。
  assert.equal(await logoutSession({ storage: s, perspective: 'ADMIN', postLogout: async () => ({ code: 200 }) }), true)
  assert.equal(s.getItem('admin:token'), null)
  assert.equal(s.getItem('unrelated'), 'keep')
})
test('目标桶缺token时回落另一桶，清理按实际桶执行', async () => {
  // 只有超管会话：商家路由回落超管桶，401/登出清理超管桶而非商家桶。
  const s = storage({ 'admin:token': 'admin-tok', 'admin:roleCode': 'SUPERADMIN' })
  assert.deepEqual(pickToken(s, 'MERCHANT'), { token: 'admin-tok', perspective: 'ADMIN' })
  assert.deepEqual(pickToken(s, 'ADMIN'), { token: 'admin-tok', perspective: 'ADMIN' })
  let seenPerspective = null
  const guard = createSessionGuard({
    storage: s,
    loadProfile: async perspective => { seenPerspective = perspective; return { id: 1, roleCode: 'SUPERADMIN' } }
  })
  assert.equal(await guard(merchantRoute), '/forbidden')
  assert.equal(seenPerspective, 'ADMIN')
  // 超管资料缓存写入实际桶（admin: 前缀），商家桶保持为空。
  assert.equal(s.getItem('admin:roleCode'), 'SUPERADMIN')
  assert.equal(s.getItem('roleCode'), null)
})
test('两桶并存时守卫按路由前缀选择目标桶', async () => {
  const seen = []
  const s = storage({ token: 'merchant-tok', 'admin:token': 'admin-tok' })
  const profiles = { ADMIN: { id: 1, roleCode: 'SUPERADMIN' }, MERCHANT: merchant }
  const guard = createSessionGuard({
    storage: s,
    loadProfile: async perspective => { seen.push(perspective); return profiles[perspective] }
  })
  assert.equal(await guard(adminRoute), true)
  assert.equal(await guard(merchantRoute), true)
  assert.deepEqual(seen, ['ADMIN', 'MERCHANT'])
  // 资料各自入桶，互不覆盖。
  assert.equal(s.getItem('admin:roleCode'), 'SUPERADMIN')
  assert.equal(s.getItem('roleCode'), 'MERCHANT_ADMIN')
})
test('一桶401清理不影响另一桶登录态', () => {
  const s = storage({ token: 'merchant-tok', 'admin:token': 'admin-tok' })
  const redirects = []
  const handlers = createApiResponseHandlers({ storage: s, base: '/', notify: () => {}, redirect: p => redirects.push(p) })
  // 商家接口 401：只清商家桶，超管桶保留，跳商家登录页。
  assert.throws(() => handlers.rejected({ config: { sessionToken: 'merchant-tok', sessionPerspective: 'MERCHANT', url: '/merchant/orders' }, response: { status: 401 } }))
  assert.equal(s.getItem('token'), null)
  assert.equal(s.getItem('admin:token'), 'admin-tok')
  assert.deepEqual(redirects, ['/login'])
  // 超管接口 401：只清超管桶，跳超管登录页。
  redirects.length = 0
  assert.throws(() => handlers.rejected({ config: { sessionToken: 'admin-tok', sessionPerspective: 'ADMIN', url: '/admin/merchants' }, response: { status: 401 } }))
  assert.equal(s.getItem('admin:token'), null)
  assert.deepEqual(redirects, ['/admin/login'])
})
test('旧版无前缀超管会话启动时迁入admin桶，商家会话不动', () => {
  // 旧版超管：无前缀 token + currentPerspective=ADMIN → 整组迁入 admin: 桶并删除旧键。
  const s = storage({ token: 'old-admin', roleCode: 'SUPERADMIN', realName: '超管', currentPerspective: 'ADMIN', unrelated: 'keep' })
  migrateLegacySession(s)
  assert.equal(s.getItem('token'), null)
  assert.equal(s.getItem('roleCode'), null)
  assert.equal(s.getItem('currentPerspective'), null)
  assert.equal(s.getItem('admin:token'), 'old-admin')
  assert.equal(s.getItem('admin:roleCode'), 'SUPERADMIN')
  assert.equal(s.getItem('admin:realName'), '超管')
  assert.equal(s.getItem('unrelated'), 'keep')
  // 旧版商家：无前缀键保持原样（零感知），迁移函数不动。
  const m = storage({ token: 'old-merchant', roleCode: 'MERCHANT_ADMIN', currentPerspective: 'MERCHANT' })
  migrateLegacySession(m)
  assert.equal(m.getItem('token'), 'old-merchant')
  assert.equal(m.getItem('admin:token'), null)
  // admin: 桶已有新会话时丢弃残留旧超管会话，防重复覆盖。
  const dup = storage({ token: 'stale', currentPerspective: 'ADMIN', 'admin:token': 'fresh' })
  migrateLegacySession(dup)
  assert.equal(dup.getItem('token'), null)
  assert.equal(dup.getItem('admin:token'), 'fresh')
})
test('视角判定优先级：显式 > 路径 > 桶内唯一token > 旧缓存 > 默认商家', () => {
  assert.equal(resolvePerspective(storage({}), 'ADMIN'), 'ADMIN')
  assert.equal(resolvePerspective(storage({ 'admin:token': 'x' })), 'ADMIN')
  assert.equal(resolvePerspective(storage({ token: 'x' })), 'MERCHANT')
  assert.equal(resolvePerspective(storage({ currentPerspective: 'ADMIN' })), 'ADMIN')
  assert.equal(resolvePerspective(storage({})), 'MERCHANT')
})

// ---------- D11 首次登录强制改密 ----------
const flaggedMerchant = { ...merchant, mustChangePassword: true }
const changePasswordRoute = { path: CHANGE_PASSWORD_PATH, meta: { title: '首次登录修改密码' } }

test('D11：会话缓存记录强制改密标记且随清理移除', async () => {
  const s = storage()
  const guard = createSessionGuard({ storage: s, loadProfile: async () => flaggedMerchant })
  await guard(merchantRoute)
  assert.equal(s.getItem('mustChangePassword'), '1')
  clearSession(s)
  assert.equal(s.getItem('mustChangePassword'), null)
})

test('D11：标记账号访问业务页一律改道改密页（携带实际桶视角），改密页放行', async () => {
  const s = storage()
  const guard = createSessionGuard({ storage: s, loadProfile: async () => flaggedMerchant })
  assert.equal(await guard(merchantRoute), `${CHANGE_PASSWORD_PATH}?p=MERCHANT`)
  assert.equal(await guard(adminRoute), `${CHANGE_PASSWORD_PATH}?p=MERCHANT`)
  assert.equal(await guard({ path: '/' }), `${CHANGE_PASSWORD_PATH}?p=MERCHANT`)
  assert.equal(await guard(changePasswordRoute), true)
})

test('D11：超管标记账号改道改密页携带ADMIN视角', async () => {
  const s = storage({ 'admin:token': 'admin-tok' })
  const guard = createSessionGuard({ storage: s, loadProfile: async () => ({ id: 1, roleCode: 'SUPERADMIN', mustChangePassword: true }) })
  assert.equal(await guard(adminRoute), `${CHANGE_PASSWORD_PATH}?p=ADMIN`)
  // 超管无商家会话访问商家页：回落超管桶，改密页仍携带实际桶视角。
  assert.equal(await guard(merchantRoute), `${CHANGE_PASSWORD_PATH}?p=ADMIN`)
})

test('D11：清除标记后访问改密页回主页，业务页正常放行', async () => {
  const s = storage()
  const guard = createSessionGuard({ storage: s, loadProfile: async () => ({ ...merchant, mustChangePassword: false }) })
  assert.equal(await guard(changePasswordRoute), '/merchant/dashboard')
  assert.equal(await guard(merchantRoute), true)
})
