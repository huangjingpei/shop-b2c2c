/*
 * 文件：shared/clipboard.mjs
 * 用途：商城端与管理端共用的剪贴板复制工具，统一安全上下文判断、降级与失败提示语义。
 * 维护注意：
 *   - 优先使用 navigator.clipboard.writeText，且仅在 window.isSecureContext 为真时；
 *     非安全上下文或写入失败时降级到 document.execCommand('copy') 临时 textarea。
 *   - 两种方式都失败时只返回 { ok:false, reason:'unsupported' }，由调用方提示“手动长按复制”；
 *     禁止把待复制内容写入日志、异常或提示文本，避免卡密/领取凭证泄漏。
 *   - 依赖可注入（navigator/document/window），便于 Node 规则测试；不访问网络，不改变调用方 UI。
 *   - 本文件为仓库根 shared/ 运行时模块，两端通过 vite 别名 @shared 引用；修改时须同步两端构建。
 */
export async function copyText(text, deps = {}) {
  const value = typeof text === 'string' ? text : text == null ? '' : String(text)
  if (!value) return { ok: false, reason: 'empty' }

  const nav = deps.navigator ?? globalThis.navigator
  const doc = deps.document ?? globalThis.document
  const win = deps.window ?? globalThis.window

  if (nav?.clipboard?.writeText && win?.isSecureContext === true) {
    try {
      await nav.clipboard.writeText(value)
      return { ok: true, mode: 'clipboard' }
    } catch (e) {
      // 非安全上下文或浏览器拒绝时继续尝试 execCommand 降级
    }
  }

  if (doc && typeof doc.createElement === 'function' && typeof doc.execCommand === 'function') {
    let ta
    try {
      ta = doc.createElement('textarea')
      ta.value = value
      ta.style.position = 'fixed'
      ta.style.left = '-9999px'
      doc.body.appendChild(ta)
      ta.focus()
      ta.select()
      const copied = doc.execCommand('copy')
      doc.body.removeChild(ta)
      if (copied === true) return { ok: true, mode: 'execCommand' }
    } catch (e) {
      if (ta && doc?.body?.contains?.(ta)) doc.body.removeChild(ta)
    }
  }

  return { ok: false, reason: 'unsupported' }
}
