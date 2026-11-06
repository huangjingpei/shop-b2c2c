/*
 * 文件：vite.config.js
 * 用途：前端Vite构建base、别名和开发API代理配置。
 * 维护注意：开发代理不是生产Nginx配置；部署路径通过VITE_APP_BASE控制，修改base须检查路由刷新与静态资源，禁止把真实密钥写入前端变量。
 * 开发代理会移除浏览器Origin头：后端按精确白名单校验Origin，用LAN IP访问时Origin不在默认白名单会被403；
 * 浏览器对同源页面发起的请求本不需要CORS，移除后由后端按非跨域请求处理。生产必须由Nginx/网关正确传递并配置白名单。
 * T28：新增 @shared 别名指向仓库根 shared/，并放开 dev server 对该目录的读取；运行时共享模块仅限无浏览器副作用、可 Node 测试的工具。
 */
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'

export default defineConfig({
  base: process.env.VITE_APP_BASE || '/',
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
      '@shared': path.resolve(__dirname, '../shared')
    }
  },
  server: {
    port: 3001,
    fs: { allow: [path.resolve(__dirname, '..')] },
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        configure: (proxy) => {
          // 仅开发代理：浏览器同源请求经代理转发时后端仍会看到原Origin，
          // 用LAN IP访问会被后端CORS白名单拒绝(403，且不会进入GlobalExceptionHandler日志)。
          proxy.on('proxyReq', (proxyReq) => proxyReq.removeHeader('origin'))
        }
      }
    }
  }
})
