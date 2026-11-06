/*
 * 文件：storeStatusActions.test.mjs
 * 用途：O6 店铺状态机前端规则回归：动作可用性按状态收口、原因码字典校验、OTHER 必填原因、
 *       解封强制双人提示、审计时间线展示口径。
 * 维护注意：只测纯函数，不调用接口；状态迁移/原因码/双人复核最终以后端 MerchantStoreStatusService 用例为准。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  STORE_ACTION_LABELS,
  STORE_REASON_CODES,
  STORE_STATUS,
  STORE_STATUS_ACTIONS,
  auditView,
  availableStatusActions,
  requiresDualApproval,
  validateStoreStatusAction
} from '../shop-admin-vue/src/utils/storeStatusActions.mjs'

test('动作可用性按状态收口：营业/打烊可冻结与关店注销，冻结只可解封，终态无动作', () => {
  assert.deepEqual(availableStatusActions(STORE_STATUS.OPEN), ['FREEZE', 'CLOSE', 'CANCEL'])
  assert.deepEqual(availableStatusActions(STORE_STATUS.PAUSED), ['FREEZE', 'CLOSE', 'CANCEL'])
  assert.deepEqual(availableStatusActions(STORE_STATUS.FROZEN), ['UNFREEZE'])
  assert.deepEqual(availableStatusActions(STORE_STATUS.PENDING_OPEN), [])
  assert.deepEqual(availableStatusActions(STORE_STATUS.CANCELLED), [])
  assert.deepEqual(availableStatusActions(undefined), [])
})

test('原因码字典与后端口径一致且原因码必填', () => {
  const values = STORE_REASON_CODES.map(item => item.value)
  for (const expected of ['VIOLATION', 'RISK_COMPLAINT', 'ARREARS', 'EXPIRED_LICENSE', 'MERCHANT_REQUEST',
    'QUALIFICATION_EXPIRED', 'STRATEGY_ADJUST', 'ILLEGAL_OPERATION', 'REPEATED_VIOLATION', 'SUSPECTED_RISK', 'OTHER']) {
    assert.ok(values.includes(expected), `缺少原因码 ${expected}`)
  }
  assert.equal(validateStoreStatusAction({ action: 'FREEZE', reasonCode: '', reason: '' }).ok, false)
  assert.equal(validateStoreStatusAction({ action: 'FREEZE', reasonCode: 'INVENTED', reason: '' }).ok, false)
  assert.equal(validateStoreStatusAction({ action: 'PAUSE', reasonCode: 'VIOLATION', reason: '' }).ok, false,
    '打烊/恢复走旧接口，本模块不接受')
})

test('OTHER 原因码必须填写具体原因；正常动作生成请求体', () => {
  assert.equal(validateStoreStatusAction({ action: 'CANCEL', reasonCode: 'OTHER', reason: ' ' }).ok, false)
  const other = validateStoreStatusAction({ action: 'CANCEL', reasonCode: 'OTHER', reason: '  平台清退  ' })
  assert.equal(other.ok, true)
  assert.deepEqual(other.payload, { action: 'CANCEL', reasonCode: 'OTHER', reason: '平台清退' })

  const freeze = validateStoreStatusAction({ action: 'FREEZE', reasonCode: 'VIOLATION', reason: '' })
  assert.equal(freeze.ok, true)
  assert.deepEqual(freeze.payload, { action: 'FREEZE', reasonCode: 'VIOLATION', reason: null })

  const tooLong = validateStoreStatusAction({ action: 'UNFREEZE', reasonCode: 'MERCHANT_REQUEST', reason: 'x'.repeat(513) })
  assert.equal(tooLong.ok, false)
})

test('仅解封强制双人复核，关店/注销不强制', () => {
  assert.equal(requiresDualApproval('UNFREEZE'), true)
  assert.equal(requiresDualApproval('FREEZE'), false)
  assert.equal(requiresDualApproval('CLOSE'), false)
  assert.equal(requiresDualApproval('CANCEL'), false)
})

test('审计时间线展示：原因码中文、复核方式与系统任务口径', () => {
  const dual = auditView({
    action: 'UNFREEZE', reasonCode: 'MERCHANT_REQUEST', approvalMode: 'DUAL_APPROVAL',
    operatorId: 7, confirmedBy: 9
  })
  assert.equal(dual.actionLabel, STORE_ACTION_LABELS.UNFREEZE)
  assert.equal(dual.reasonLabel, 'MERCHANT_REQUEST（商家申请）')
  assert.equal(dual.approvalLabel, '双人复核')
  assert.equal(dual.operatorLabel, '操作人 #7')
  assert.equal(dual.confirmedByLabel, '复核人 #9')

  const system = auditView({ action: 'FREEZE', reasonCode: 'EXPIRED_LICENSE', approvalMode: null, operatorId: null })
  assert.equal(system.operatorLabel, '系统任务')
  assert.equal(system.approvalLabel, '同人记录')
  assert.equal(auditView({ action: 'PAUSE', reasonCode: null }).reasonLabel, '')
})
