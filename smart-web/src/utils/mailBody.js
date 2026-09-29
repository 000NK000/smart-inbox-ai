import createDOMPurify from 'dompurify'

// The sanitizer runs last. The result is used only inside a sandboxed iframe.
export function renderMailBody(html, text, compact = true, hostWindow = window) {
  const purifier = createDOMPurify(hostWindow)
  const doc = new hostWindow.DOMParser().parseFromString(html || '<body></body>', 'text/html')
  if (!html) {
    const pre = doc.createElement('pre')
    pre.textContent = text || '暂无可显示的正文。'
    pre.style.cssText = 'white-space:pre-wrap;overflow-wrap:anywhere;font:15px/1.7 system-ui'
    doc.body.append(pre)
  }
  let hidden = 0
  if (compact) {
    // Only small legal/footer blocks: never remove an ancestor containing the main order.
    const legal = /^(?:©\s*\d{4}|copyright\s*(?:©|\d)|all rights reserved|privacy policy\s*$|隐私政策\s*$|unsubscribe\s*$)/i
    for (const el of [...doc.body.querySelectorAll('p,div,td,footer')]) {
      const value = el.textContent.replace(/\s+/g, ' ').trim()
      if (!el.isConnected || value.length > 1500 || !legal.test(value)) continue
      if (el.querySelector('h1,h2,h3') || /(?:track package|arriving|order\s*#|total\s*\$)/i.test(value)) continue
      el.remove()
      hidden++
    }
    // Standalone legal/navigation links; keep tracking, product and invoice links.
    for (const link of doc.body.querySelectorAll('a')) {
      if (/^(?:privacy policy|unsubscribe|your account|your orders|buy again|隐私政策|退订)$/i.test(link.textContent.trim())) {
        let parent = link.parentElement
        link.remove(); hidden++
        while (parent && parent !== doc.body && !parent.textContent.trim() && !parent.querySelector('img,svg')) {
          const next = parent.parentElement
          parent.remove()
          parent = next
        }
      }
    }
  }
  for (const link of doc.querySelectorAll('a')) {
    if (!/^(https?:\/\/|mailto:)/i.test(link.getAttribute('href') || '')) link.removeAttribute('href')
    link.setAttribute('target', '_blank')
    link.setAttribute('rel', 'noopener noreferrer')
  }
  for (const img of doc.querySelectorAll('img')) {
    const src = img.getAttribute('src') || ''
    const tiny = ['width', 'height'].some(key => img.hasAttribute(key) && Number(img.getAttribute(key)) <= 1)
    if (tiny || !/^(https:\/\/|data:image\/(?:png|jpeg|gif|webp);base64,)/i.test(src)) { img.remove(); continue }
    img.removeAttribute('srcset')
    img.setAttribute('referrerpolicy', 'no-referrer')
    img.style.maxWidth = '100%'
  }
  // Keep email layout CSS, but strip active/external CSS constructs and attributes.
  for (const el of doc.querySelectorAll('*')) {
    for (const name of ['background', 'srcset', 'ping']) el.removeAttribute(name)
    if (/url\s*\(|@import|expression\s*\(|\\/.test(el.getAttribute('style') || '')) el.removeAttribute('style')
  }
  for (const style of doc.querySelectorAll('style')) {
    if (/url\s*\(|@import|expression\s*\(|\\/.test(style.textContent)) style.remove()
  }
  const safe = purifier.sanitize(doc.documentElement.outerHTML, {
    WHOLE_DOCUMENT: true, USE_PROFILES: { html: true },
    ADD_ATTR: ['target', 'referrerpolicy'],
    FORBID_TAGS: ['script', 'iframe', 'object', 'embed', 'form', 'input', 'button', 'textarea', 'select', 'meta', 'base', 'link', 'audio', 'video'],
    FORBID_ATTR: ['srcdoc', 'autofocus'],
  })
  const policy = "default-src 'none'; img-src https: data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'"
  const head = `<meta http-equiv="Content-Security-Policy" content="${policy}"><meta name="referrer" content="no-referrer"><style>html,body{margin:0;padding:12px;background:white;color:#202b3b;font-family:Arial,sans-serif;overflow-wrap:anywhere}body{box-sizing:border-box}img{max-width:100%}table{max-width:100%}a{cursor:pointer}pre{white-space:pre-wrap}</style>`
  return { document: safe.replace(/<head>/i, `<head>${head}`), hidden }
}
