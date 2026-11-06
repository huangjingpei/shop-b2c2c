/*
 * 文件：adminAccount.mjs
 * 用途：T31 商户开通表单的临时管理员口令生成与基础校验，替代固定 admin123。
 * 维护注意：只在浏览器端生成初始口令，不写日志、不落本地存储；必须包含大写、小写、数字和符号。
 *           随机源可注入仅用于单元测试，生产调用不传参数使用 Math.random。
 */
const UPPER = 'ABCDEFGHJKLMNPQRSTUVWXYZ'
const LOWER = 'abcdefghijkmnopqrstuvwxyz'
const DIGITS = '23456789'
const SYMBOLS = '!@#$%^&*'

export function generateTemporaryPassword(length = 16, random = Math.random) {
  const size = Math.max(12, Math.min(24, Number(length) || 16))
  const groups = [UPPER, LOWER, DIGITS, SYMBOLS]
  const all = groups.join('')
  const pick = source => source[Math.floor(random() * source.length)]
  const password = groups.map(pick)
  while (password.length < size) password.push(pick(all))
  // 打乱固定前缀，避免口令总是以大写字母开头
  for (let i = password.length - 1; i > 0; i -= 1) {
    const j = Math.floor(random() * (i + 1))
    ;[password[i], password[j]] = [password[j], password[i]]
  }
  return password.join('')
}

export function isStrongPassword(value) {
  const text = String(value || '')
  return text.length >= 8 && /[A-Za-z]/.test(text) && /\d/.test(text)
}
