/*
 * 文件：peerPayCashier.test.mjs
 * 用途：T28 收银台视图规则测试：token 切换清空旧海报、迟到账单/海报不得回写、失败态可重试、复制走共享降级工具。
 * 维护注意：以源码契约断言为主（组合式页面不易无浏览器渲染），不使用真实网络、剪贴板或存储；修改页面结构时同步更新本文件。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../shop-storefront-vue/src/views/PeerPayCashier.vue', import.meta.url), 'utf8')

test('复制统一使用共享剪贴板工具且不拼接原文', () => {
  assert.ok(source.includes("from '@shared/clipboard.mjs'"))
  assert.equal(source.includes('fallbackCopy'), false)
  assert.equal(source.includes('复制失败，请手动长按复制：'), false)
})

test('token 切换/重试时先清空旧海报', () => {
  const resetIndex = source.indexOf('const resetPoster =')
  assert.ok(resetIndex >= 0, '必须定义 resetPoster')
  assert.ok(source.indexOf('resetPoster()', resetIndex + 'const resetPoster ='.length) >= 0, 'loadDetail 必须调用 resetPoster')
  assert.ok(source.includes("posterBase64.value = ''"), '切换 token 必须清空海报')
  assert.ok(source.includes('posterSequence++'))
})

test('迟到的账单与海报响应不得覆盖当前 token', () => {
  assert.ok(source.includes('sequence !== detailSequence'))
  assert.ok(source.includes('sequence !== posterSequence'))
  assert.ok(source.includes('if (sequence === detailSequence) loading.value = false'))
  assert.ok(source.includes('if (sequence === posterSequence) posterLoading.value = false'))
})

test('失败态提供重试且卸载时作废未完成请求', () => {
  assert.ok(source.includes('@click="loadDetail()"'), '错误卡片必须有重新加载按钮')
  const unmounted = source.slice(source.indexOf('onUnmounted('))
  assert.ok(unmounted.includes('detailSequence++') && unmounted.includes('posterSequence++'))
  assert.equal(source.includes('快速支付暂未开放') || source.includes('支付暂未开放'), true)
})

test('T29 付款前预占、保存 ownerToken、离开/超时释放', () => {
  assert.ok(source.includes('peerPayReservation.mjs'), '必须复用预占凭据工具')
  assert.ok(source.includes('buildPreLockHeaders({ ownerToken, attemptKey })'), 'pre-lock 必须带尝试键并在恢复时带 ownerToken')
  assert.ok(source.includes('saveOwnerToken(token, data.ownerToken)'), '成功后必须持久化服务端 ownerToken')
  assert.ok(source.includes('ensureAttemptKey(token)'), '尝试键与 ownerToken 分离生成')
  assert.ok(source.includes('buildCancelHeaders(ownerToken)'), '取消必须显式提交所有权凭证')
  assert.ok(source.includes('reservation.value?.ownerToken || readOwnerToken(token)'), '刷新后从 sessionStorage 恢复 ownerToken')
  const unmounted = source.slice(source.indexOf('onUnmounted('))
  assert.ok(unmounted.includes('releaseReservationFor('), '卸载时必须释放预占')
  const timer = source.slice(source.indexOf('const startCountdownTimer'))
  assert.ok(timer.includes('clearOwnerToken(token)') && timer.includes('clearAttemptKey(token)'), '倒计时结束必须清理本地凭据')
  assert.ok(source.includes('刷新账单后重试'), '预占失败必须提供刷新路径')
})
