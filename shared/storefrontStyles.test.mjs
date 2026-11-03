/*
 * 文件：storefrontStyles.test.mjs
 * 用途：用真实PostCSS/Tailwind链验证商城源码扫描、品牌色、响应式及动态分支样式。
 * 维护注意：先在shop-storefront-vue按锁文件npm ci；测试只在内存生成CSS，不访问网络或写dist。
 * 直接加载项目PostCSS配置和实际源码，不能用手工CSS替代断言；生产Vite集成和浏览器视觉仍须用户单独验证。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const require = createRequire(new URL('../shop-storefront-vue/package.json', import.meta.url))
const postcss = require('postcss')
const config = require('./postcss.config.cjs')
const cssUrl = new URL('../shop-storefront-vue/src/styles/tailwind.css', import.meta.url)
// 所有用例共用一次真实转换，避免每条断言重复扫描和生成样式。
let compilation
function compileStyles() {
  compilation ??= (async () => {
    const plugins = Object.entries(config.plugins).map(([name, options]) => require(name)(options))
    return postcss(plugins).process(await readFile(cssUrl, 'utf8'), { from: fileURLToPath(cssUrl) })
  })()
  return compilation
}

function declaration(root, selector, property) {
  const values = []
  root.walkRules(rule => {
    if (rule.selector === selector) rule.walkDecls(property, item => values.push(item.value))
  })
  return values.join(';')
}

test('HTML启动壳不加载外部CSS运行时，main导入本地样式', async () => {
  const html = await readFile(new URL('../shop-storefront-vue/index.html', import.meta.url), 'utf8')
  const main = await readFile(new URL('../shop-storefront-vue/src/main.js', import.meta.url), 'utf8')
  assert.doesNotMatch(html, /<script[^>]+src=["'](?:https?:)?\/\//i)
  assert.doesNotMatch(html, /tailwind\.config\s*=/)
  assert.match(main, /import\s+['"]\.\/styles\/tailwind\.css['"]/)
})

test('实际源码生成品牌底色、文字及hover样式，保留颜色值', async () => {
  const { root } = await compileStyles()
  assert.match(declaration(root, '.bg-brand-500', 'background-color'), /249 115 22/)
  assert.match(declaration(root, '.text-brand-700', 'color'), /194 65 12/)
  assert.match(declaration(root, '.hover\\:bg-brand-600:hover', 'background-color'), /234 88 12/)
  assert.match(declaration(root, '.hover\\:border-brand-300:hover', 'border-color'), /253 186 116/)
})

test('index任意背景色和卡片截断不因内容裁剪丢失', async () => {
  const { root } = await compileStyles()
  let arbitraryBackground = false
  root.walkRules(rule => {
    if (rule.selector.includes('f8fafc')) {
      rule.walkDecls('background-color', item => { arbitraryBackground ||= /248 250 252/.test(item.value) })
    }
  })
  assert.ok(arbitraryBackground, '必须扫描index.html中的body背景')
  assert.equal(declaration(root, '.line-clamp-2', '-webkit-line-clamp'), '2')
})

test('响应式网格保留断点与列数', async () => {
  const { root } = await compileStyles()
  const expected = new Map([
    ['.sm\\:grid-cols-2', ['640px', 'repeat(2,minmax(0,1fr))']],
    ['.lg\\:grid-cols-4', ['1024px', 'repeat(4,minmax(0,1fr))']]
  ])
  for (const [selector, [breakpoint, columns]] of expected) {
    let found = false
    root.walkRules(rule => {
      if (rule.selector !== selector) return
      assert.equal(rule.parent.name, 'media')
      assert.ok(rule.parent.params.includes(breakpoint))
      rule.walkDecls('grid-template-columns', item => {
        assert.equal(item.value.replace(/\s/g, ''), columns)
        found = true
      })
    })
    assert.ok(found, `缺少响应式规则：${selector}`)
  }
})

test('Vue动态class字面量的两个分支和disabled状态均生成', async () => {
  const { root } = await compileStyles()
  assert.equal(declaration(root, '.translate-x-5', '--tw-translate-x'), '1.25rem')
  assert.equal(declaration(root, '.translate-x-0', '--tw-translate-x'), '0px')
  assert.match(declaration(root, '.disabled\\:bg-slate-300:disabled', 'background-color'), /203 213 225/)
})

test('本地转换处理全部Tailwind指令，字体保持系统字体且无网络import', async () => {
  const { root, css } = await compileStyles()
  const directives = []
  root.walkAtRules('tailwind', item => directives.push(item.params))
  assert.deepEqual(directives, [])
  assert.match(declaration(root, 'body', 'font-family'), /BlinkMacSystemFont/)
  assert.doesNotMatch(css, /@import\s+(?:url\()?\s*['"]?https?:/i)
})
