/*
 * 文件：portalLinks.mjs
 * 用途：商城前台跳转到商家工作台/登录页的地址解析。
 * 维护注意：生产默认使用同源部署路径 /console/login；开发态由 Vite 独立端口运行，需指向管理端 3001，否则在 3002 下点击会 404。
 * 部署若调整管理端挂载路径，必须同步设置 VITE_MERCHANT_PORTAL_URL，不要在组件里再写死第二套地址。
 */
export function resolveMerchantPortalUrl({ configured, dev, protocol, hostname } = {}) {
  const trimmed = typeof configured === 'string' ? configured.trim() : ''
  if (trimmed) return trimmed
  if (dev) return `${protocol || 'http:'}//${hostname || 'localhost'}:3001/login`
  return '/console/login'
}
