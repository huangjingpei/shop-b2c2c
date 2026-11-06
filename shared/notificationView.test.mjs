/*
 * 文件：notificationView.test.mjs
 * 用途：O8 通知渲染规则回归：payload 安全解析降级、各模板标题/级别/正文行、
 *       SLA 档位与店铺状态动作的 tagType 口径。
 * 维护注意：只测纯函数；真实通知内容由后端写入侧用例保证，前端渲染不得重新发明语义。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { NOTIFICATION_TITLES, notificationView, parsePayload } from '../shop-admin-vue/src/utils/notificationView.mjs'

test('parsePayload 字符串与对象均可，非法输入降级为空对象', () => {
  assert.deepEqual(parsePayload('{"a":1}'), { a: 1 })
  assert.deepEqual(parsePayload({ a: 1 }), { a: 1 })
  assert.deepEqual(parsePayload('not-json'), {})
  assert.deepEqual(parsePayload(null), {})
  assert.deepEqual(parsePayload('[]'), {})
})

test('SLA 通知展示档位、等待时长与申请单号，升级为 danger', () => {
  const reminder = notificationView({
    bizType: 'REVIEW_SLA_BREACH',
    templateCode: 'REVIEW_SLA_BREACH',
    payloadJson: JSON.stringify({ applicationId: 7, storeName: '旗舰店', level: 'REVIEW_SLA_REMINDER', waitingHours: 25, tip: '请尽快处理' })
  })
  assert.equal(reminder.title, '开店审核超时')
  assert.equal(reminder.tagType, 'warning')
  assert.ok(reminder.lines.some(line => line.includes('25 小时') && line.includes('超时提醒')))
  assert.ok(reminder.lines.some(line => line.includes('#7')))

  const escalation = notificationView({
    templateCode: 'REVIEW_SLA_BREACH',
    payloadJson: JSON.stringify({ applicationId: 8, level: 'REVIEW_SLA_ESCALATION', waitingHours: 50 })
  })
  assert.equal(escalation.tagType, 'danger')
})

test('店铺状态变更通知展示动作与原因码，冻结/注销为 danger、解封为 success', () => {
  const freeze = notificationView({
    templateCode: 'STORE_STATUS_CHANGED',
    payloadJson: JSON.stringify({ action: 'FREEZE', reasonCode: 'VIOLATION', reason: '抽检不合格', tip: '店铺已被冻结' })
  })
  assert.equal(freeze.tagType, 'danger')
  assert.ok(freeze.lines.some(line => line.includes('VIOLATION（违规经营）')))
  assert.ok(freeze.lines.some(line => line.includes('抽检不合格')))

  const unfreeze = notificationView({
    templateCode: 'STORE_STATUS_CHANGED',
    payloadJson: JSON.stringify({ action: 'UNFREEZE', reasonCode: 'MERCHANT_REQUEST' })
  })
  assert.equal(unfreeze.tagType, 'success')
})

test('开店节点通知：驳回 danger、补件 warning、开通展示账号与登录地址', () => {
  const rejected = notificationView({
    templateCode: 'ONBOARDING_REJECTED',
    payloadJson: JSON.stringify({ rejectCode: 'BLURRY_PHOTO', reason: '证件照片模糊' })
  })
  assert.equal(rejected.tagType, 'danger')
  assert.ok(rejected.lines.some(line => line.includes('BLURRY_PHOTO')))

  const supplement = notificationView({ templateCode: 'ONBOARDING_SUPPLEMENT', payloadJson: '{}' })
  assert.equal(supplement.tagType, 'warning')

  const opened = notificationView({
    templateCode: 'ONBOARDING_OPENED',
    payloadJson: JSON.stringify({ username: '13800000000', loginPath: '/merchant/login', tip: '已开通' })
  })
  assert.equal(opened.tagType, 'success')
  assert.ok(opened.lines.some(line => line.includes('13800000000')))
  assert.ok(opened.lines.some(line => line.includes('/merchant/login')))
  assert.ok(!opened.lines.join('').includes('password'), '开通通知绝不展示密码')
})

test('未知模板与空负载有降级展示', () => {
  const view = notificationView({ templateCode: 'INVENTED', payloadJson: null })
  assert.equal(view.title, 'INVENTED')
  assert.deepEqual(view.lines, ['通知详情暂不可用'])
  assert.equal(notificationView(null).title, '系统通知')
  assert.ok(Object.keys(NOTIFICATION_TITLES).length >= 8, '模板标题映射需覆盖 O8/O9 全部节点')
})
