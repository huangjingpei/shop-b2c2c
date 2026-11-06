/*
 * 文件：peerPayReservation.test.mjs
 * 用途：T29 预占凭据规则测试：ownerToken 与尝试键分离、按 token 隔离、刷新恢复头、失败文案与存储降级。
 * 维护注意：使用内存伪 storage，不触碰真实 sessionStorage、浏览器或后端；令牌值均为测试常量。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  ownerStorageKey, attemptStorageKey, readOwnerToken, saveOwnerToken, clearOwnerToken,
  readAttemptKey, saveAttemptKey, clearAttemptKey, newAttemptKey, ensureAttemptKey,
  buildPreLockHeaders, buildCancelHeaders, reservationFailureView
} from '../shop-storefront-vue/src/utils/peerPayReservation.mjs'

function fakeStorage() {
  const data = new Map()
  return {
    data,
    getItem: key => data.get(key) ?? null,
    setItem: (key, value) => data.set(key, String(value)),
    removeItem: key => data.delete(key)
  }
}

const fixedRandom = () => 0.5

test('ownerToken 与尝试键相互独立且按 token 隔离', () => {
  const storage = fakeStorage()
  saveOwnerToken('tok-a', 'owner-a', storage)
  saveAttemptKey('tok-a', 'attempt-a', storage)
  saveOwnerToken('tok-b', 'owner-b', storage)

  assert.equal(readOwnerToken('tok-a', storage), 'owner-a')
  assert.equal(readAttemptKey('tok-a', storage), 'attempt-a')
  assert.equal(readOwnerToken('tok-b', storage), 'owner-b')
  assert.equal(readAttemptKey('tok-b', storage), '')
  assert.notEqual(ownerStorageKey('tok-a'), attemptStorageKey('tok-a'))
  assert.notEqual('owner-a', 'attempt-a', '所有权凭证不得复用尝试键')
})

test('刷新恢复时 pre-lock 头同时带尝试键与 ownerToken', () => {
  assert.deepEqual(buildPreLockHeaders({ ownerToken: 'owner', attemptKey: 'attempt' }),
    { 'Idempotency-Key': 'attempt', 'X-Reservation-Token': 'owner' })
  assert.deepEqual(buildPreLockHeaders({ ownerToken: '', attemptKey: 'attempt' }), { 'Idempotency-Key': 'attempt' })
  assert.deepEqual(buildPreLockHeaders({ ownerToken: '', attemptKey: '' }), {})
  assert.deepEqual(buildCancelHeaders(''), {})
})

test('尝试键可重试复用、长度不超过 64 且仅含字母数字', () => {
  const storage = fakeStorage()
  const first = ensureAttemptKey('tok-share', storage, fixedRandom)
  const second = ensureAttemptKey('tok-share', storage, fixedRandom)
  assert.equal(first, second)
  assert.ok(first.length <= 64)
  assert.match(first, /^pp-[A-Za-z0-9]+-[a-z0-9]{16}$/)
  assert.equal(newAttemptKey('tok-share', () => 0.999).length <= 64, true)
})

test('取消后清理本地凭据，避免下次误带过期 token', () => {
  const storage = fakeStorage()
  saveOwnerToken('tok', 'owner', storage)
  saveAttemptKey('tok', 'attempt', storage)
  clearOwnerToken('tok', storage)
  clearAttemptKey('tok', storage)
  assert.equal(readOwnerToken('tok', storage), '')
  assert.equal(readAttemptKey('tok', storage), '')
})

test('存储不可用时降级为内存，不抛异常', () => {
  const broken = {
    getItem: () => { throw new Error('blocked') },
    setItem: () => { throw new Error('blocked') },
    removeItem: () => { throw new Error('blocked') }
  }
  saveOwnerToken('tok-mem', 'owner-mem', broken)
  assert.equal(readOwnerToken('tok-mem', broken), 'owner-mem')
})

test('预占失败文案区分被占、凭据失效与网络错误，不泄露原始错误', () => {
  assert.equal(reservationFailureView({ response: { status: 409, data: { message: '订单已被其他付款人预占中，请稍候再试' } } }).refresh, true)
  assert.equal(reservationFailureView({ response: { status: 403 } }).title, '预占凭据无效')
  const generic = reservationFailureView({ message: 'ownerToken=secret' })
  assert.equal(generic.title, '预占失败')
  assert.equal(generic.detail.includes('ownerToken'), false)
})
