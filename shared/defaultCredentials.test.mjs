/*
 * 文件：defaultCredentials.test.mjs
 * 用途：锁定“前端不预填演示账号、结构基线不含演示凭据、演示种子独立且仅幂等写入”的T03约束。
 * 维护注意：仅读取仓库内文本文件，不启动应用、不连接数据库；真实生产profile启动与首次管理员登录仍由人工验证。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const read = path => readFileSync(join(root, path), 'utf8')
const login = read('shop-admin-vue/src/views/login/Login.vue')
const schema = read('shop-b2b2c-backend/src/main/resources/db/schema.sql')
const demoSeed = read('shop-b2b2c-backend/src/main/resources/db/demo-seed.sql')
const prodConfig = read('shop-b2b2c-backend/src/main/resources/application-prod.yml')

test('登录页不预填演示账号或口令', () => {
  assert.equal(login.includes('admin123'), false)
  assert.equal(login.includes('superadmin'), false)
  assert.equal(login.includes('quickFill'), false)
  assert.match(login, /username:\s*''/)
  assert.match(login, /password:\s*''/)
})

test('结构基线不含演示凭据/固定超管/明文卡密', () => {
  assert.equal(schema.includes('admin123'), false)
  assert.equal(schema.includes('superadmin'), false)
  assert.equal(schema.includes('INSERT INTO `sys_user`'), false)
  assert.equal(schema.includes('INSERT INTO `product_virtual_card`'), false)
  assert.equal(schema.includes("VALUES (1, NULL, 'superadmin'"), false)
})

test('演示种子独立存放并标注生产禁用', () => {
  assert.equal(demoSeed.includes('禁止在生产库执行'), true)
  assert.equal(demoSeed.includes('INSERT IGNORE INTO `sys_user`'), true)
  assert.equal(demoSeed.includes('admin123'), true)
})

test('生产profile关闭自动SQL初始化', () => {
  assert.match(prodConfig, /on-profile:\s*prod/)
  assert.match(prodConfig, /mode:\s*never/)
})
