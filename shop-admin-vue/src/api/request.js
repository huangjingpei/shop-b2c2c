/*
 * 文件：request.js
 * 用途：封装管理端Axios请求、Bearer令牌及错误处理。
 * 维护注意：令牌来自本地存储但权限由后端决定；注意业务code与HTTP状态区别，兼容HTTP和业务码401，只清理本应用身份键并保留部署base；旧请求不能清理新登录；响应/错误日志不能泄露凭证。
 */
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { createApiResponseHandlers } from '../utils/authSession.mjs'

const service = axios.create({
  baseURL: '/api/v1',
  timeout: 10000
})

service.interceptors.request.use(
  config => {
    const token = localStorage.getItem('token')
    config.sessionToken = token
    if (token && config.url !== '/auth/login') {
      config.headers['Authorization'] = 'Bearer ' + token
    }
    return config
  },
  error => {
    return Promise.reject(error)
  }
)

const responseHandlers = createApiResponseHandlers({
  storage: localStorage,
  notify: message => ElMessage.error(message),
  redirect: path => window.location.assign(path),
  base: import.meta.env.BASE_URL
})
service.interceptors.response.use(responseHandlers.fulfilled, responseHandlers.rejected)

export default service
