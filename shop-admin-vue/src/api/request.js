/*
 * 文件：request.js
 * 用途：封装管理端Axios请求、Bearer令牌及错误处理。
 * 维护注意：令牌来自本地存储但权限由后端决定；注意业务code与HTTP状态区别，兼容HTTP和业务码401；旧请求不能清理新登录；响应/错误日志不能泄露凭证。
 * 会话隔离：/admin/** 接口使用超管桶（admin: 前缀键），/merchant/** 接口使用商家桶（原始键名）；目标桶无 token 时回落另一桶，
 * 保住“超管无商家会话仍可查看商家工作台”的语义；config.sessionPerspective 记录实际发出 token 的桶，401 只清理该桶。
 */
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { createApiResponseHandlers, pickToken, resolvePerspective, PERSPECTIVE_ADMIN, PERSPECTIVE_MERCHANT } from '../utils/authSession.mjs'

const service = axios.create({
  baseURL: '/api/v1',
  timeout: 10000
})

// 接口前缀决定目标视角；/auth/** 等无前缀接口按页面上下文判定，也可用 config.perspective 显式指定（如改密页携带 ?p= 视角）。
function perspectiveForRequest(config) {
  const explicit = String(config.perspective || '').toUpperCase()
  if (explicit === PERSPECTIVE_ADMIN || explicit === PERSPECTIVE_MERCHANT) return explicit
  const url = String(config.url || '')
  if (/^\/admin(\/|$)/.test(url)) return PERSPECTIVE_ADMIN
  if (/^\/merchant(\/|$)/.test(url)) return PERSPECTIVE_MERCHANT
  return resolvePerspective(localStorage)
}

service.interceptors.request.use(
  config => {
    const picked = pickToken(localStorage, perspectiveForRequest(config))
    config.sessionToken = picked.token
    config.sessionPerspective = picked.perspective
    if (picked.token && config.url !== '/auth/login') {
      config.headers['Authorization'] = 'Bearer ' + picked.token
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
