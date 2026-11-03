/*
 * 文件：postcss.config.cjs
 * 用途：让Vite在本地通过Tailwind及Autoprefixer生成商城CSS。
 * 维护注意：显式定位同目录Tailwind配置，避免从仓库根构建时漏扫源码；CJS不受package.json的type=module影响。依赖只在构建/测试时运行，不注入浏览器CDN脚本。
 */
const path = require('node:path')

module.exports = {
  plugins: {
    tailwindcss: { config: path.join(__dirname, 'tailwind.config.cjs') },
    autoprefixer: {}
  }
}
