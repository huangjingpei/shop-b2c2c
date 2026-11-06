/*
 * 文件：idempotency.mjs
 * 用途：为一次页面建单生成可复用的HTTP幂等键，网络失败时保留，成功后结束本次提交。
 * 维护注意：幂等键须用安全随机源；同参数重试复用，不同参数新建。请求体须用prepare返回的快照，避免表单在发送前被修改；仅在确认业务成功后调用complete，不在finally重置；状态仅存当前页面内存，跨页面调用方应自行保留键。
 *           crypto.randomUUID 只在安全上下文（HTTPS/localhost）可用；通过局域网 IP 的 http 页面访问时不存在，
 *           必须退回同为密码学随机源的 crypto.getRandomValues 组装 UUIDv4，否则建单会在发送请求前抛错并提示“下单失败”。
 */
export function createIdempotencyKey(cryptoSource = globalThis.crypto) {
  if (typeof cryptoSource?.randomUUID === 'function') {
    return cryptoSource.randomUUID()
  }
  if (typeof cryptoSource?.getRandomValues !== 'function') {
    throw new Error('当前浏览器不支持安全随机源，无法生成幂等键')
  }
  const bytes = new Uint8Array(16)
  cryptoSource.getRandomValues(bytes)
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export function createIdempotentSubmission(factory = createIdempotencyKey) {
  let fingerprint, key
  return {
    prepare(payload) {
      const next = JSON.stringify(payload)
      const body = JSON.parse(next)
      if (key === undefined || next !== fingerprint) {
        key = factory()
        fingerprint = next
      }
      return { body, headers: { 'Idempotency-Key': key } }
    },
    complete() { fingerprint = undefined; key = undefined }
  }
}
