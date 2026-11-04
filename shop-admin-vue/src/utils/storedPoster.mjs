/*
 * 文件：storedPoster.mjs
 * 用途：商家已存海报按需读取及预览状态控制，供订单页复用。
 * 维护注意：请求只携订单ID，租户由后端校验；只允许现有PNG Base64格式，拒绝外链/SVG等来源。
 * 关闭/切换/卸载会使旧响应失效，不存localStorage、不记录异常原文；格式检查不证明PNG内容有效，图片解码失败另行处理。
 */
export function canPreviewPoster(order) {
  return [1, 2].includes(order?.peerPayType) && typeof order?.peerPayToken === 'string' && !!order.peerPayToken.trim()
}

function orderId(value) {
  const text = typeof value === 'string' ? value : Number.isSafeInteger(value) ? String(value) : ''
  return /^[1-9]\d*$/.test(text) && BigInt(text) <= 9223372036854775807n ? text : null
}

function isPng(value) {
  if (typeof value !== 'string' || !/^data:image\/png;base64,[A-Za-z0-9+/]+={0,2}$/.test(value)) return false
  return value.slice('data:image/png;base64,'.length).length % 4 === 0
}

export function createStoredPosterPreview({ get, render }) {
  let version = 0
  let state = { visible: false, loading: false, url: '', error: '' }
  const publish = next => { state = next; render({ ...next }) }
  return {
    async open(order) {
      const requestVersion = ++version
      publish({ visible: true, loading: true, url: '', error: '' })
      const id = orderId(order?.id)
      if (!id) {
        publish({ visible: true, loading: false, url: '', error: '订单编号不正确' })
        return
      }
      try {
        const response = await get(`/merchant/orders/${id}/poster`, { skipErrorToast: true })
        if (response?.code !== 200) {
          const error = new Error('poster unavailable'); error.businessCode = response?.code; throw error
        }
        if (!isPng(response.data)) throw new Error('invalid poster format')
        if (requestVersion === version) publish({ visible: true, loading: false, url: response.data, error: '' })
      } catch (error) {
        const missing = error?.businessCode === 404 || error?.response?.data?.code === 404 || error?.response?.status === 404
        if (requestVersion === version) publish({ visible: true, loading: false, url: '',
          error: missing ? '未找到已保存海报' : '海报加载失败，请关闭后重试' })
      }
    },
    close() { version++; publish({ visible: false, loading: false, url: '', error: '' }) },
    imageFailed(url) {
      if (!state.visible || state.url !== url) return
      version++; publish({ visible: true, loading: false, url: '', error: '海报图片无法显示，请关闭后重试' })
    }
  }
}
