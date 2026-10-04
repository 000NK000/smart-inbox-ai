const CACHE = 'smart-inbox-public-v1'
const PUBLIC_FILES = ['/offline.html', '/manifest.webmanifest', '/icons/icon.svg', '/icons/icon-192.png', '/icons/icon-512.png', '/icons/apple-touch-icon.png']
const PUBLIC_PATHS = new Set(PUBLIC_FILES)
function cacheable(url) {
  return url.origin === self.location.origin && !url.search && (PUBLIC_PATHS.has(url.pathname) || /^\/assets\/[^/]+\.(?:js|css|woff2?|svg|png)$/.test(url.pathname))
}
self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(PUBLIC_FILES)).then(() => self.skipWaiting()))
})
self.addEventListener('activate', event => {
  event.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(key => key.startsWith('smart-inbox-public-') && key !== CACHE).map(key => caches.delete(key)))).then(() => self.clients.claim()))
})
self.addEventListener('fetch', event => {
  const request = event.request, url = new URL(request.url)
  // API requests, authorization callbacks, uploads, and external resources are
  // always handled by the network and never copied into any service-worker cache.
  if (request.method !== 'GET' || url.origin !== self.location.origin || /^\/api(?:\/|$)/.test(url.pathname)) return
  if (request.mode === 'navigate') {
    event.respondWith(fetch(request).catch(() => caches.match('/offline.html')))
    return
  }
  if (!cacheable(url)) return
  event.respondWith(caches.open(CACHE).then(async cache => {
    const cached = await cache.match(request)
    if (cached) return cached
    const response = await fetch(request)
    if (response.ok && response.type === 'basic') await cache.put(request, response.clone())
    return response
  }))
})
