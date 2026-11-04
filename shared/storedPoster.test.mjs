/*
 * 文件：storedPoster.test.mjs
 * 用途：已存海报预览的合法读取、错误格式、失败清理和异步竞态回归。
 * 维护注意：注入请求及渲染函数，不访问网络或真实图像解码；占位PNG Data URL仅验证格式。浏览器解码和下载须人工检查，不记录令牌或海报正文到日志。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { canPreviewPoster, createStoredPosterPreview } from '../shop-admin-vue/src/utils/storedPoster.mjs'

const png = 'data:image/png;base64,aW1hZ2U='
function viewer(get) {
  let state
  const preview = createStoredPosterPreview({ get, render: value => { state = value } })
  return { preview, state: () => state }
}
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

test('代付/推单依据小字段显示入口，不依赖列表海报正文', () => {
  for (const peerPayType of [1, 2]) assert.equal(canPreviewPoster({ peerPayType, peerPayToken: 'fixture' }), true)
  for (const order of [null, {}, { peerPayType: 0, peerPayToken: 'fixture' }, { peerPayType: 1, peerPayToken: ' ' }]) {
    assert.equal(canPreviewPoster(order), false)
  }
})
test('只携订单ID请求，先清空上次海报再显示合法结果', async () => {
  const pending = deferred(); const calls = []
  const view = viewer((...args) => { calls.push(args); return pending.promise })
  const open = view.preview.open({ id: 7, merchantId: 999, peerPayToken: 'not-in-url' })
  assert.deepEqual(view.state(), { visible: true, loading: true, url: '', error: '' })
  pending.resolve({ code: 200, data: png }); await open
  assert.deepEqual(calls, [['/merchant/orders/7/poster', { skipErrorToast: true }]])
  assert.equal(view.state().url, png); assert.equal(view.state().loading, false)
})
for (const id of [null, 0, -1, 1.2, Number.MAX_SAFE_INTEGER + 1, '01', '1/other', '9223372036854775808']) {
  test(`非法ID不请求：${String(id)}`, async () => {
    const view = viewer(() => assert.fail('不得请求'))
    await view.preview.open({ id })
    assert.equal(view.state().error, '订单编号不正确'); assert.equal(view.state().url, '')
  })
}
test('Long最大值字符串保持精度', async () => {
  const view = viewer(async url => {
    assert.equal(url, '/merchant/orders/9223372036854775807/poster'); return { code: 200, data: png }
  })
  await view.preview.open({ id: '9223372036854775807' }); assert.equal(view.state().url, png)
})
for (const data of [null, '', 'https://example.test/image.png', 'javascript:alert(1)', 'data:image/svg+xml;base64,AAAA',
  'data:image/png;base64,', 'data:image/png;base64,abc', 'data:image/png;base64,a===']) {
  test(`非PNG数据或非法Base64不用于img/download：${String(data)}`, async () => {
    const view = viewer(async () => ({ code: 200, data }))
    await view.preview.open({ id: 7 }); assert.equal(view.state().url, ''); assert.ok(view.state().error)
  })
}
test('业务及HTTP失败不显示原始异常或旧海报', async () => {
  for (const get of [async () => ({ code: 404 }), async () => { throw { businessCode: 404 } },
    async () => { throw { response: { status: 404 } } }]) {
    const view = viewer(get); await view.preview.open({ id: 7 })
    assert.equal(view.state().error, '未找到已保存海报')
  }
  const view = viewer(async () => { throw new Error('private-token') })
  await view.preview.open({ id: 7 }); assert.equal(view.state().error, '海报加载失败，请关闭后重试')
  for (const response of [null, { code: 500, message: 'private-details' }]) {
    const failure = viewer(async () => response); await failure.preview.open({ id: 7 })
    assert.equal(failure.state().url, ''); assert.equal(failure.state().error, '海报加载失败，请关闭后重试')
  }
})
test('快速切换订单时迟到响应不能覆盖当前结果', async () => {
  const first = deferred(); const second = deferred()
  const view = viewer(url => url.includes('/1/') ? first.promise : second.promise)
  const older = view.preview.open({ id: 1 }); const newer = view.preview.open({ id: 2 })
  second.resolve({ code: 200, data: png }); await newer
  first.reject(new Error('old request')); await older
  assert.equal(view.state().url, png); assert.equal(view.state().error, '')
})
test('关闭/卸载使未完成请求失效并清理内容', async () => {
  const pending = deferred(); const view = viewer(() => pending.promise)
  const open = view.preview.open({ id: 7 }); view.preview.close()
  pending.resolve({ code: 200, data: png }); await open
  assert.deepEqual(view.state(), { visible: false, loading: false, url: '', error: '' })
})
test('解码失败清空下载地址，旧img错误不影响其他海报', async () => {
  const view = viewer(async () => ({ code: 200, data: png }))
  await view.preview.open({ id: 7 })
  view.preview.imageFailed('old-url'); assert.equal(view.state().url, png)
  view.preview.imageFailed(png); assert.equal(view.state().url, ''); assert.ok(view.state().error)
})
