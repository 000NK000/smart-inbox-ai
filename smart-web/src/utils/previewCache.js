const PREFIX = 'smart-inbox.preview.'
export function readPreview(key, maxAge = 86400000) {
  try { const data = JSON.parse(localStorage.getItem(PREFIX + key)); return data && Date.now() - data.time < maxAge ? data.value : null } catch { return null }
}
export function savePreview(key, value) {
  try { localStorage.setItem(PREFIX + key, JSON.stringify({ time: Date.now(), value })) } catch { /* Cache is optional, never user-authored data. */ }
}
