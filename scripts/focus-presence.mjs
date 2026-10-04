import { request } from 'node:http'
import { randomUUID } from 'node:crypto'

// Keep time from the desktop process, not a browser timer that can be throttled
// when another page is open or the window is minimized to the tray.
export function createFocusPresence(runtime, options = {}) {
  const { gatewayPort = 8080, intervalMs = 15000, makeId = randomUUID,
    zone = Intl.DateTimeFormat().resolvedOptions().timeZone,
    schedule = setInterval, unschedule = clearInterval,
    post = (path, body) => postFocus(gatewayPort, path, body) } = options
  let running = false, blocked = false, runtimeId = null, timer = null, inFlight = null, stopping = null, unsubscribe

  function tick() {
    if (!running || blocked || runtime.snapshot().mode !== 'active') return Promise.resolve()
    if (inFlight) return inFlight
    runtimeId ||= makeId()
    const id = runtimeId
    // Errors are retried on the next heartbeat. The backend lease bounds gaps;
    // never replay missed heartbeats or fabricate time while it was offline.
    inFlight = Promise.resolve().then(() => post('/api/focus/presence', { runtimeId: id, zone }))
      .catch(() => {}).finally(() => { inFlight = null })
    return inFlight
  }

  function pause() {
    blocked = true
    if (stopping) return stopping
    const id = runtimeId
    runtimeId = null
    stopping = Promise.resolve(inFlight).then(async () => {
      if (id) await post('/api/focus/presence/stop', { runtimeId: id, zone })
    }).catch(() => {}).finally(() => { stopping = null })
    return stopping
  }

  async function afterTransition() {
    await stopping
    if (!running) return
    blocked = false
    await tick()
  }

  function start() {
    if (running) return inFlight || Promise.resolve()
    running = true; blocked = false
    unsubscribe = runtime.addLifecycleListener?.({ beforeTransition: pause, afterTransition })
    timer = schedule(() => { void tick() }, intervalMs)
    timer?.unref?.()
    return tick()
  }

  async function close() {
    running = false
    if (timer !== null) unschedule(timer)
    timer = null
    unsubscribe?.(); unsubscribe = undefined
    await pause()
  }

  return { start, close, tick }
}

function postFocus(port, path, body) {
  return new Promise((accept, reject) => {
    const json = JSON.stringify(body)
    const req = request({ hostname: '127.0.0.1', port, path, method: 'POST', agent: false,
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(json) } }, res => {
      res.resume()
      res.on('end', () => res.statusCode >= 200 && res.statusCode < 300 ? accept() : reject(new Error('Focus presence unavailable')))
      res.on('error', reject)
    })
    // A total deadline also covers a connected server that never finishes its response.
    const deadline = setTimeout(() => req.destroy(new Error('Focus presence timed out')), 5000)
    req.once('close', () => clearTimeout(deadline))
    req.once('error', reject)
    req.end(json)
  })
}
