export const MOBILE_AUTH_REQUIRED = 'smart-inbox:mobile-auth-required'
export const MOBILE_RESUME = 'smart-inbox:mobile-resume'
const contentLocks = new Set()
const hiddenPortals = new Map()
let portalObserver

// Existing dialogs teleport outside #app. Hide and disable those nodes without
// unmounting them, then restore their exact accessibility state after reconnect.
export function setMobileContentLock(reason, active) {
  if (active) contentLocks.add(reason); else contentLocks.delete(reason)
  const locked = contentLocks.size > 0
  document.documentElement.classList.toggle('mobile-content-locked', locked)
  const hidePortals = () => {
    for (const element of document.body.children) {
      if (element.id === 'app' || ['SCRIPT', 'STYLE', 'LINK'].includes(element.tagName)) continue
      if (!hiddenPortals.has(element)) hiddenPortals.set(element, { inert: element.hasAttribute('inert'), hidden: element.getAttribute('aria-hidden') })
      element.setAttribute('inert', ''); element.setAttribute('aria-hidden', 'true')
    }
  }
  if (locked) {
    hidePortals()
    if (!portalObserver && typeof MutationObserver !== 'undefined') { portalObserver = new MutationObserver(hidePortals); portalObserver.observe(document.body, { childList: true }) }
  } else {
    portalObserver?.disconnect(); portalObserver = null
    for (const [element, previous] of hiddenPortals) {
      if (!previous.inert) element.removeAttribute('inert')
      if (previous.hidden === null) element.removeAttribute('aria-hidden'); else element.setAttribute('aria-hidden', previous.hidden)
    }
    hiddenPortals.clear()
  }
}

export async function readJson(url, options = {}, timeout = 15000) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeout)
  try {
    const response = await fetch(url, { credentials: 'same-origin', cache: 'no-store', ...options, signal: controller.signal })
    let data
    try { data = await response.json() } catch { data = {} }
    if (!response.ok) {
      const error = new Error(typeof data.message === 'string' ? data.message : '暂时无法连接电脑，请稍后重试。')
      error.status = response.status
      throw error
    }
    return data
  } catch (error) {
    if (error.name === 'AbortError') throw new Error('连接超时，请确认电脑仍在运行。')
    throw error
  } finally { clearTimeout(timer) }
}

// Identity uses a server HttpOnly cookie. Pairing codes and business data are
// never copied to browser storage. Keep mounted drafts behind the pairing UI.
export function installMobileAuthGuard(axios, { isMobile, isLocked, onExpired }) {
  if (!axios.interceptors) return () => {}
  const isApi = config => {
    try {
      const url = new URL(config?.url || '', window.location.origin)
      return url.origin === window.location.origin && url.pathname.startsWith('/api/')
    } catch { return false }
  }
  const request = axios.interceptors.request.use(config => {
    if (isMobile() && isLocked() && isApi(config)) return Promise.reject(Object.assign(new Error('请先连接此设备。'), { mobileLocked: true, config }))
    return config
  })
  const response = axios.interceptors.response.use(value => value, error => {
    if (isMobile() && isApi(error.config) && error.response?.status === 401) onExpired()
    return Promise.reject(error)
  })
  return () => { axios.interceptors.request.eject(request); axios.interceptors.response.eject(response) }
}
