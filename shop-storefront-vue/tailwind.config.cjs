/*
 * 文件：tailwind.config.cjs
 * 用途：商城Tailwind 3构建扫描范围与既有品牌配色，替代浏览器CDN配置。
 * 维护注意：使用CJS适配type=module项目；扫描路径基于本文件并规范化Windows分隔符，不能依赖调用目录。
 * 动态class须在Vue/JS中保留完整字面量；新增品牌色或大范围safelist需审阅体积和视觉变化，不从后端数据拼接样式。
 */
const path = require('node:path')
const source = relative => path.resolve(__dirname, relative).split(path.sep).join('/')

module.exports = {
  content: [source('./index.html'), source('./src/**/*.{vue,js,mjs}')],
  theme: {
    extend: {
      colors: {
        brand: {
          50: '#fff7ed',
          100: '#ffedd5',
          // Store.vue已使用该色阶作悬停边框，旧CDN配置遗漏；补齐同一橙色系。
          300: '#fdba74',
          500: '#f97316',
          600: '#ea580c',
          700: '#c2410c'
        }
      }
    }
  },
  plugins: []
}
