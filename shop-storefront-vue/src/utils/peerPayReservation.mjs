/*
 * 文件：peerPayReservation.mjs
 * 用途：T29 收银台预占凭据管理：按分享 token 隔离持久化 ownerToken 与尝试键，构造 pre-lock/cancel-lock 请求头。
 * 维护注意：
 *  - ownerToken 是恢复/取消预占的所有权凭证，只存 sessionStorage；禁止写 localStorage、URL、日志或埋点。
 *  - Idempotency-Key（尝试键）与 X-Reservation-Token（ownerToken）必须是两个独立值；尝试键只用于后端留痕。
 *  - 存储不可用（隐私模式/配额）时降级为内存 Map，保证当前页面仍能完成一次预占。
 *  - 刷新后必须带 ownerToken 再调 pre-lock，否则后端会把同一付款人误判为“已被其他付款人预占中”。
 *  - D27：详情页带 ownerToken 调 GET detail，由服务端返回 lockOwnedByCaller；前端只在 true 时展示本人预占，
 *    false 时显示“他人预占”并禁用抢锁。ownerToken 仍不得出现在任何响应/URL/日志中。
 *  - 上线顺序：后端 T10（8874b6a，X-Reservation-Token 契约）必须先于本前端发布；禁止前端先发，
 *    否则新前端向旧后端回传 ownerToken 不会被识别，刷新恢复仍会 409 误报。
 */

const OWNER_PREFIX = 'peerpay:owner:'
const ATTEMPT_PREFIX = 'peerpay:attempt:'
const memory = new Map()

const getStore = storage => storage ?? (typeof sessionStorage !== 'undefined' ? sessionStorage : null)

function read(store, key) {
  if (store) {
    try {
      const value = store.getItem(key)
      if (value) return value
    } catch (e) {
      // 存储被禁用时读取内存降级
    }
  }
  return memory.get(key) ?? ''
}

function write(store, key, value) {
  memory.set(key, value)
  if (store) {
    try { store.setItem(key, value) } catch (e) { /* 隐私模式仅保留内存副本 */ }
  }
}

function remove(store, key) {
  memory.delete(key)
  if (store) {
    try { store.removeItem(key) } catch (e) { /* 忽略无法删除 */ }
  }
}

export const ownerStorageKey = token => OWNER_PREFIX + token
export const attemptStorageKey = token => ATTEMPT_PREFIX + token

export function readOwnerToken(token, storage) {
  if (!token) return ''
  return read(getStore(storage), ownerStorageKey(token))
}

export function saveOwnerToken(token, ownerToken, storage) {
  if (!token || !ownerToken) return
  write(getStore(storage), ownerStorageKey(token), ownerToken)
}

export function clearOwnerToken(token, storage) {
  if (!token) return
  remove(getStore(storage), ownerStorageKey(token))
}

export function readAttemptKey(token, storage) {
  if (!token) return ''
  return read(getStore(storage), attemptStorageKey(token))
}

export function saveAttemptKey(token, attemptKey, storage) {
  if (!token || !attemptKey) return
  write(getStore(storage), attemptStorageKey(token), attemptKey)
}

export function clearAttemptKey(token, storage) {
  if (!token) return
  remove(getStore(storage), attemptStorageKey(token))
}

/**
 * 生成一次付款尝试的幂等键（≤64 字符）。仅使用字母数字，避免头部编码问题；与 ownerToken 完全独立。
 */
export function newAttemptKey(token, random = Math.random) {
  const alphabet = 'abcdefghijklmnopqrstuvwxyz0123456789'
  let suffix = ''
  for (let i = 0; i < 16; i++) {
    const index = Math.min(alphabet.length - 1, Math.max(0, Math.floor(random() * alphabet.length)))
    suffix += alphabet[index]
  }
  const prefix = String(token || '').replace(/[^A-Za-z0-9]/g, '').slice(0, 8)
  return `pp-${prefix}-${suffix}`
}

export function ensureAttemptKey(token, storage, random = Math.random) {
  const existing = readAttemptKey(token, storage)
  if (existing) return existing
  const generated = newAttemptKey(token, random)
  saveAttemptKey(token, generated, storage)
  return generated
}

/**
 * pre-lock 请求头：尝试键始终发送；仅在本地已有 ownerToken（刷新恢复）时发送所有权凭证。
 */
export function buildPreLockHeaders({ ownerToken, attemptKey }) {
  const headers = {}
  if (attemptKey) headers['Idempotency-Key'] = attemptKey
  if (ownerToken) headers['X-Reservation-Token'] = ownerToken
  return headers
}

export function buildCancelHeaders(ownerToken) {
  return ownerToken ? { 'X-Reservation-Token': ownerToken } : {}
}

/**
 * 预占失败的可展示文案与刷新提示；不把原始错误对象或令牌带进 UI。
 */
export function reservationFailureView(error) {
  const status = error?.response?.status
  const message = error?.response?.data?.message
  if (status === 409) {
    return { title: '库存已被占用', detail: message || '该账单已被其他付款人预占，请稍后刷新账单再试', refresh: true }
  }
  if (status === 403) {
    return { title: '预占凭据无效', detail: message || '当前付款凭据已失效，请刷新账单后重试', refresh: true }
  }
  return { title: '预占失败', detail: message || '库存预占失败，请刷新账单后重试', refresh: true }
}
