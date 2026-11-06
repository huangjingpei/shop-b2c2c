/*
 * 文件：clipboard.test.mjs
 * 用途：shared/clipboard.mjs 的规则测试，覆盖安全上下文优先、非安全上下文降级、双失败与空内容。
 * 维护注意：全部使用注入的伪 navigator/document，不访问真实剪贴板与网络；失败结果不得包含待复制原文。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { copyText } from './clipboard.mjs'

const makeDoc = (execResult) => {
  const appended = []
  return {
    appended,
    createElement: () => ({ value: '', style: {}, focus() {}, select() {} }),
    execCommand: () => execResult,
    body: { appendChild: node => appended.push(node), removeChild: node => appended.splice(appended.indexOf(node), 1), contains: node => appended.includes(node) }
  }
}

test('安全上下文优先使用 Clipboard API', async () => {
  const written = []
  const result = await copyText('hello', {
    navigator: { clipboard: { writeText: async text => written.push(text) } },
    window: { isSecureContext: true },
    document: makeDoc(false)
  })
  assert.deepEqual(result, { ok: true, mode: 'clipboard' })
  assert.deepEqual(written, ['hello'])
})

test('非安全上下文不调用 Clipboard API，降级到 execCommand', async () => {
  let clipboardCalls = 0
  const doc = makeDoc(true)
  const result = await copyText('secret-card', {
    navigator: { clipboard: { writeText: async () => { clipboardCalls++ } } },
    window: { isSecureContext: false },
    document: doc
  })
  assert.equal(clipboardCalls, 0)
  assert.deepEqual(result, { ok: true, mode: 'execCommand' })
  assert.equal(doc.appended.length, 0, '临时节点必须移除')
})

test('Clipboard API 抛错时降级，且不泄露原文', async () => {
  const doc = makeDoc(true)
  const result = await copyText('card-123', {
    navigator: { clipboard: { writeText: async () => { throw new Error('denied card-123') } } },
    window: { isSecureContext: true },
    document: doc
  })
  assert.deepEqual(result, { ok: true, mode: 'execCommand' })
})

test('两种方式都失败时返回 unsupported，不包含原文', async () => {
  const result = await copyText('card-999', {
    navigator: { clipboard: { writeText: async () => { throw new Error('no') } } },
    window: { isSecureContext: true },
    document: makeDoc(false)
  })
  assert.equal(result.ok, false)
  assert.equal(result.reason, 'unsupported')
  assert.equal(JSON.stringify(result).includes('card-999'), false)
})

test('空内容不触发复制', async () => {
  let called = 0
  const result = await copyText('', { navigator: { clipboard: { writeText: async () => { called++ } } }, window: { isSecureContext: true } })
  assert.equal(result.ok, false)
  assert.equal(result.reason, 'empty')
  assert.equal(called, 0)
})
