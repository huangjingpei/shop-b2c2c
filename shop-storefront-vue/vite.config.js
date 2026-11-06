/*
 * 文件：vite.config.js
 * 用途：前端Vite构建base、别名和开发API代理配置。
 * 维护注意：开发代理不是生产Nginx配置；部署路径通过VITE_APP_BASE控制，修改base须检查路由刷新与静态资源，禁止把真实密钥写入前端变量。
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
    port: 3002,
    fs: { allow: [path.resolve(__dirname, '..')] },
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
