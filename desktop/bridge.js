(() => {
  // This narrow bridge is injected by our desktop shell, only into its own top page.
  // Email frames and external pages must never receive native app controls.
  if (window.top !== window || location.origin !== 'http://127.0.0.1:5173' || !window.chrome?.webview) return
  Object.defineProperty(window, 'smartInboxDesktop', {
    value: Object.freeze({
      setLocale(locale) {
        if (locale !== 'zh-CN' && locale !== 'en-US') return false
        window.chrome.webview.postMessage('smart-inbox:locale:' + locale)
        return true
      },
      exit() {
        if (!navigator.userActivation?.isActive) return false
        window.chrome.webview.postMessage('smart-inbox:exit')
        return true
      }
    }),
    writable: false,
    configurable: false
  })
})()
