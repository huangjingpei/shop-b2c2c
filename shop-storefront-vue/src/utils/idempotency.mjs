/*
 * 文件：idempotency.mjs
 * 用途：为一次页面建单生成可复用的HTTP幂等键，网络失败时保留，成功后结束本次提交。
 * 维护注意：幂等键须用安全随机源；同参数重试复用，不同参数新建。请求体须用prepare返回的快照，避免表单在发送前被修改；仅在确认业务成功后调用complete，不在finally重置；状态仅存当前页面内存，跨页面调用方应自行保留键。
 */
export function createIdempotentSubmission(factory = () => globalThis.crypto.randomUUID()) {
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
