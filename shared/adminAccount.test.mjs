/*
 * 文件：adminAccount.test.mjs
 * 用途：T31 商户管理员临时口令生成测试：长度下限、四类字符覆盖、可复现随机源与强口令校验。
 * 维护注意：口令规则只做前端初筛，最终以后端校验为准；测试不得写入任何真实账号或固定口令。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { generateTemporaryPassword, isStrongPassword } from '../shop-admin-vue/src/utils/adminAccount.mjs'

function seededRandom(seed = 7) {
  let value = seed
  return () => {
    value = (value * 1103515245 + 12345) % 2147483648
    return value / 2147483648
  }
}

test('临时口令长度不低于 12 且包含四类字符', () => {
  const password = generateTemporaryPassword(16, seededRandom())
  assert.equal(password.length, 16)
  assert.match(password, /[A-Z]/)
  assert.match(password, /[a-z]/)
  assert.match(password, /\d/)
  assert.match(password, /[!@#$%^&*]/)
  assert.notEqual(password, 'admin123')
})

test('传入过短长度时仍按最小 12 位生成', () => {
  assert.equal(generateTemporaryPassword(6, seededRandom()).length, 12)
})

test('相同随机序列可复现，便于排查', () => {
  const first = generateTemporaryPassword(16, seededRandom(9))
  const second = generateTemporaryPassword(16, seededRandom(9))
  assert.equal(first, second)
})

test('强口令校验拒绝固定弱默认值', () => {
  assert.equal(isStrongPassword('admin123'), true)
  assert.equal(isStrongPassword('12345678'), false)
  assert.equal(isStrongPassword('abcdefgh'), false)
  assert.equal(isStrongPassword(''), false)
  assert.equal(isStrongPassword(generateTemporaryPassword(16, seededRandom())), true)
})
