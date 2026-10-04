import { createServer, request } from 'node:http'
import { createConnection } from 'node:net'
import { createHash, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto'
import { createReadStream, existsSync, mkdirSync, readFileSync, writeFileSync, renameSync, chmodSync, realpathSync, statSync } from 'node:fs'
import { join, resolve, sep, extname } from 'node:path'
import { spawnSync } from 'node:child_process'
import { inspectMobileNetwork, configureMobileNetwork } from './mobile-network.mjs'

const COOKIE = '__Host-SmartInboxMobile'
const SESSION_MS = 30 * 24 * 60 * 60 * 1000
const PAIR_MS = 10 * 60 * 1000
const LOOPBACK = new Set(['127.0.0.1', '::1', '::ffff:127.0.0.1'])
const ID = '[A-Za-z0-9_-]{1,100}'
const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.webmanifest': 'application/manifest+json' }
const pages = new Set(['/', '/index.html', '/inbox', '/tasks', '/calendar', '/jobs', '/focus', '/practice', '/news', '/watch', '/stocks'])

// Explicitly authorized business methods only. New backend endpoints stay private.
const routes = [
  ['GET', '/api/mails/(summaries|revision|reminders|task-plan|task-plan/progress|[0-9]+)'],
  ['POST', '/api/mails/([0-9]+/(analysis|reminder-ack)|task-plan/(refresh|retry))'],
  ['PATCH', '/api/mails/[0-9]+/(read|star|snooze)'],
  ['GET', '/api/tasks(/summary)?'], ['POST', '/api/tasks(/from-mail)?'],
  ['PUT|DELETE', `/api/tasks/${ID}`], ['PATCH', `/api/tasks/${ID}/completion`],
  ['GET', '/api/calendar'], ['POST', '/api/calendar/events'], ['PUT|DELETE', `/api/calendar/events/${ID}`],
  ['GET|POST', '/api/job-applications'], ['PUT|DELETE', `/api/job-applications/${ID}`],
  ['PUT|DELETE', `/api/job-applications/${ID}/mails/[0-9]+`], ['POST', '/api/job-applications/analysis'],
  ['POST', '/api/job-applications/suggestions/[0-9]+/(apply|dismiss)'],
  ['GET', '/api/focus'], ['POST', '/api/focus/switch'],
  ['GET', '/api/practice(/(groups|solutions))?'], ['POST', '/api/practice/(groups|attempts)'],
  ['PATCH', '/api/practice/[0-9]+/group'], ['DELETE', '/api/practice/[0-9]+'],
  ['GET|PUT', '/api/practice/[0-9]+/solution'], ['GET', `/api/practice/solution-images/${ID}`],
  ['GET', '/api/dashboard/(news|us-news|trends|weather|watch|watchlist|preferences)'],
  ['POST', '/api/dashboard/(us-news/(brief|translate)|weather/analysis|watchlist)'],
  ['PUT', '/api/dashboard/preferences'], ['PUT|DELETE', `/api/dashboard/watchlist/${ID}`],
  ['GET', `/api/dashboard/watch/douban/poster/${ID}`], ['POST', '/api/chat'],
  ['GET', '/api/stocks/(session|status|portfolio|history|watchlist|reports)'],
  ['POST', '/api/stocks/(portfolio/refresh|watchlist|reports)'],
  ['PUT|DELETE', `/api/stocks/watchlist/${ID}`], ['DELETE', `/api/stocks/reports/${ID}`]
].map(([methods, path]) => [new Set(methods.split('|')), new RegExp(`^${path}$`)])
const forbidden = /\/(?:credentials|backup|import|export|replace|auth|runtime|mobile-admin|actuator|internal|operations)(?:\/|$)/
const digest = value => createHash('sha256').update(value).digest('hex')
function equal(a, b) {
  const x = Buffer.from(String(a || '')), y = Buffer.from(String(b || ''))
  return x.length === y.length && timingSafeEqual(x, y)
}
function pathname(url) {
  if (typeof url !== 'string' || url.length > 8192 || !url.startsWith('/') || /[\\#\x00-\x20\x7f]/.test(url)) return null
  const path = url.split('?')[0]
  // Downstream must never decode or normalize a different route.
  if (!/^\/[A-Za-z0-9_./-]*$/.test(path) || path.includes('//') || path.split('/').some(part => part === '.' || part === '..')) return null
  return path
}
function json(res, status, value) {
  if (res.destroyed || res.writableEnded) return
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer' })
  res.end(JSON.stringify(value))
}
const failure = (res, status, message) => json(res, status, { mobile: true, message })
function readBody(req, limit, timeout = 30000) {
  return new Promise((accept, reject) => {
    let size = 0, chunks = [], settled = false
    const finish = (error, result) => {
      if (settled) return
      settled = true; clearTimeout(timer)
      req.off('data', data); req.off('end', end); req.off('error', fail); req.off('aborted', fail)
      if (error) { req.resume(); reject(error) } else accept(result)
    }
    const data = chunk => { size += chunk.length; if (size > limit) finish(Object.assign(new Error('Too large'), { status: 413 })); else chunks.push(chunk) }
    const end = () => finish(null, Buffer.concat(chunks))
    const fail = () => finish(Object.assign(new Error('Request interrupted'), { status: 400 }))
    const timer = setTimeout(() => finish(Object.assign(new Error('Request timeout'), { status: 408 })), timeout)
    if (Number(req.headers['content-length']) > limit) { finish(Object.assign(new Error('Too large'), { status: 413 })); return }
    req.on('data', data); req.once('end', end); req.once('error', fail); req.once('aborted', fail)
  })
}

// Only hashes are persisted. Restrict the directory and existing children to this OS user.
function protectDirectory(directory) {
  mkdirSync(directory, { recursive: true, mode: 0o700 })
  if (process.platform !== 'win32') { chmodSync(directory, 0o700); return }
  const script = "$ErrorActionPreference='Stop'; $sid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User; $acl=New-Object System.Security.AccessControl.DirectorySecurity; $acl.SetAccessRuleProtection($true,$false); $rule=New-Object System.Security.AccessControl.FileSystemAccessRule($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow'); $acl.AddAccessRule($rule); [System.IO.Directory]::SetAccessControl($env:SMART_INBOX_MOBILE_DIRECTORY,$acl); foreach($path in [System.IO.Directory]::GetFiles($env:SMART_INBOX_MOBILE_DIRECTORY)) { $f=New-Object System.Security.AccessControl.FileSecurity; $f.SetAccessRuleProtection($true,$false); $f.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($sid,'FullControl','Allow'))); [System.IO.File]::SetAccessControl($path,$f) }"
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], { windowsHide: true, timeout: 15000, encoding: 'utf8', env: { ...process.env, SMART_INBOX_MOBILE_DIRECTORY: directory } })
  if (result.error || result.status !== 0) throw new Error('Unable to protect mobile session storage')
}

export function createMobileAccess(root, options = {}) {
  const { runtime, gatewayPort = 8080, mobilePort = 5174, now = Date.now, secureDirectory = protectDirectory,
    inspectNetwork = inspectMobileNetwork, configureNetwork = configureMobileNetwork } = options
  if (!runtime?.snapshot) throw new Error('A shared runtime controller is required')
  const directory = join(root, '.smart-inbox', 'mobile'), sessionFile = join(directory, 'sessions.json')
  const dist = resolve(root, 'smart-web', 'dist')
  let config = null, sessions = [], pair = null, running = false, startupMessage = '', initialized = false
  let attempts = [], probeAt = -Infinity, probeResult = false, probePromise, starting
  function loadConfig() {
    try {
      const value = JSON.parse(readFileSync(join(directory, 'config.json'), 'utf8'))
      if (value.enabled !== true || !/^https:\/\/[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.[a-z0-9-]+\.ts\.net$/.test(value.origin)
        || typeof value.allowedLogin !== 'string' || !value.allowedLogin.trim() || value.allowedLogin.length > 320 || /[\x00-\x1f\x7f]/.test(value.allowedLogin)) return null
      return { origin: value.origin, host: new URL(value.origin).host, allowedLogin: value.allowedLogin }
    } catch { return null }
  }
  function save() {
    sessions = sessions.filter(s => s.expiresAt > now())
    const temporary = sessionFile + '.' + randomBytes(8).toString('hex') + '.tmp'
    writeFileSync(temporary, JSON.stringify({ version: 1, sessions }), { mode: 0o600 })
    renameSync(temporary, sessionFile)
    if (process.platform !== 'win32') chmodSync(sessionFile, 0o600)
  }
  function initialize() {
    secureDirectory(directory)
    sessions = []
    if (existsSync(sessionFile)) {
      const saved = JSON.parse(readFileSync(sessionFile, 'utf8'))
      if (saved.version !== 1 || !Array.isArray(saved.sessions) || saved.sessions.length > 100) throw new Error('Invalid session storage')
      sessions = saved.sessions.filter(s => typeof s.id === 'string' && /^[a-f0-9-]{36}$/.test(s.id) && /^[a-f0-9]{64}$/.test(s.hash) && typeof s.name === 'string' && s.name.length <= 80 && Number.isFinite(s.expiresAt) && s.expiresAt > now())
    }
    initialized = true
  }
  function status() {
    return { configured: !!config, origin: config?.origin || '', running, message: startupMessage,
      devices: sessions.filter(s => s.expiresAt > now()).map(({ id, name, createdAt, lastSeenAt }) => ({ id, name, createdAt, lastSeenAt })) }
  }
  function localAdmin(req, mutation) {
    const host = req.headers.host
    return LOOPBACK.has(req.socket.remoteAddress) && ['127.0.0.1:5173', 'localhost:5173'].includes(host)
      && (!req.headers.origin || req.headers.origin === `http://${host}`)
      && !['cross-site', 'same-site'].includes(req.headers['sec-fetch-site'])
      && (!mutation || (req.headers.origin === `http://${host}` && equal(req.headers['x-runtime-token'], runtime.snapshot().token)))
  }
  async function admin(req, res, next) {
    // Identify the desktop before runtime gating or proxying, including while the backend is offline.
    if (['GET', 'HEAD'].includes(req.method) && req.url?.split('?')[0] === '/api/mobile/context') {
      if (!pathname(req.url)) return failure(res, 400, '请求路径无效')
      if (!localAdmin(req, false)) return failure(res, 403, '仅支持本机访问')
      return json(res, 200, { mobile: false })
    }
    if (!req.url?.split('?')[0].startsWith('/api/mobile-admin')) return next()
    const path = pathname(req.url)
    if (!path) return failure(res, 400, '请求路径无效')
    if (!localAdmin(req, req.method !== 'GET')) return failure(res, 403, '请在电脑应用中管理手机访问')
    if (req.method === 'GET' && path === '/api/mobile-admin/status') return json(res, 200, { ...status(), network: await inspectNetwork() })
    if (req.method === 'POST' && path === '/api/mobile-admin/setup') {
      const result = await configureNetwork(root)
      if (result.configured) await start()
      return json(res, 200, { ...status(), ...result, running, message: result.configured && !running ? startupMessage : result.message, network: await inspectNetwork() })
    }
    if (req.method === 'POST' && path === '/api/mobile-admin/pair') {
      if (!running || !initialized) return failure(res, 409, '请先完成手机访问设置并启动应用')
      const code = randomBytes(6).toString('hex').toUpperCase()
      pair = { hash: digest(code), expiresAt: now() + PAIR_MS, attempts: 0 }
      return json(res, 200, { code, expiresAt: new Date(pair.expiresAt).toISOString(), origin: config.origin })
    }
    const match = /^\/api\/mobile-admin\/devices\/([a-f0-9-]{36})$/.exec(path)
    if (req.method === 'DELETE' && match) {
      const previous = sessions
      sessions = sessions.filter(s => s.id !== match[1])
      try { if (initialized) save() } catch { sessions = previous; return failure(res, 503, '设备撤销未保存，请重试') }
      return json(res, 200, status())
    }
    return failure(res, 404, '接口不存在')
  }
  const handleAdmin = (req, res, next) => { admin(req, res, next).catch(() => failure(res, 503, '手机访问设置暂不可用，请稍后重试')) }
  async function mode() {
    const state = runtime.snapshot()
    if (state.mode !== 'active') return { mode: state.mode, message: ({ standby: '电脑应用正在待机，请在电脑上恢复运行', entering: '电脑应用正在进入待机', resuming: '电脑应用正在恢复运行', error: '电脑应用需要处理，请回到电脑检查' })[state.mode] || '电脑应用暂不可用', mobile: true }
    if (now() - probeAt > 2000) {
      probePromise ||= new Promise(resolveProbe => {
        const socket = createConnection({ host: '127.0.0.1', port: gatewayPort })
        let done = false
        const finish = value => { if (done) return; done = true; socket.destroy(); probeAt = now(); probeResult = value; resolveProbe() }
        socket.setTimeout(1000); socket.once('connect', () => finish(true)); socket.once('error', () => finish(false)); socket.once('timeout', () => finish(false))
      }).finally(() => { probePromise = null })
      await probePromise
    }
    return { mode: probeResult ? 'active' : 'offline', message: probeResult ? '正常运行' : '电脑后台暂时离线，请稍后重试', mobile: true }
  }
  function session(req) {
    const parts = String(req.headers.cookie || '').split(';').map(s => s.trim()).filter(s => s.startsWith(COOKIE + '='))
    if (parts.length !== 1) return null
    const token = parts[0].slice(COOKIE.length + 1)
    if (!/^[A-Za-z0-9_-]{43}$/.test(token)) return null
    return sessions.find(s => s.expiresAt > now() && equal(s.hash, digest(token))) || null
  }
  function touch(device) {
    if (now() - Date.parse(device.lastSeenAt) < 60000) return
    device.lastSeenAt = new Date(now()).toISOString(); save()
  }
  function perimeter(req) {
    return running && config && LOOPBACK.has(req.socket.remoteAddress) && req.headers.host === config.host
      && equal(req.headers['tailscale-user-login'], config.allowedLogin)
      && (!req.headers.origin || req.headers.origin === config.origin)
      && !['cross-site', 'same-site'].includes(req.headers['sec-fetch-site'])
      && (['GET', 'HEAD'].includes(req.method) || req.headers.origin === config.origin)
  }
  function setCookie(res, value, age) {
    res.setHeader('Set-Cookie', `${COOKIE}=${value}; Path=/; Max-Age=${age}; Secure; HttpOnly; SameSite=Strict`)
  }
  async function pairDevice(req, res) {
    attempts = attempts.filter(time => now() - time < 60000)
    if (attempts.length >= 20) return failure(res, 429, '配对尝试过多，请一分钟后重试')
    attempts.push(now())
    const raw = await readBody(req, 4096)
    let body
    try { body = JSON.parse(raw.toString('utf8')) } catch { return failure(res, 400, '配对信息格式无效') }
    const code = typeof body?.code === 'string' ? body.code.replace(/[\s-]/g, '').toUpperCase() : ''
    if (!pair || pair.expiresAt <= now() || pair.attempts >= 10) return failure(res, 401, '配对码无效或已过期，请在电脑上重新生成')
    pair.attempts++
    if (!/^[A-F0-9]{12}$/.test(code) || !equal(pair.hash, digest(code))) return failure(res, 401, '配对码无效或已过期，请在电脑上重新生成')
    if (sessions.filter(s => s.expiresAt > now()).length >= 30) return failure(res, 409, '已配对设备过多，请在电脑上撤销旧设备')
    const token = randomBytes(32).toString('base64url')
    const name = (typeof body.deviceName === 'string' ? body.deviceName.replace(/[\x00-\x1f\x7f]/g, '').trim().slice(0, 80) : '') || 'iPhone'
    const stamp = new Date(now()).toISOString()
    const device = { id: randomUUID(), name, hash: digest(token), createdAt: stamp, lastSeenAt: stamp, expiresAt: now() + SESSION_MS }
    sessions.push(device)
    try { save() } catch { sessions = sessions.filter(s => s.id !== device.id); throw new Error('Session persistence failed') }
    pair = null; setCookie(res, token, SESSION_MS / 1000)
    return json(res, 200, { mobile: true, authenticated: true, deviceName: name })
  }
  function serveStatic(req, res, path) {
    if (!['GET', 'HEAD'].includes(req.method)) return failure(res, 405, '请求方法不支持')
    if (path.split('/').some(part => part.startsWith('.'))) return failure(res, 404, '页面不存在')
    const extension = extname(path)
    if (!pages.has(path) && path !== '/offline.html' && !(extension && mime[extension] && extension !== '.html')) return failure(res, 404, '页面不存在')
    let file = pages.has(path) ? join(dist, 'index.html') : resolve(dist, '.' + path)
    if (!file.startsWith(dist + sep)) return failure(res, 403, '页面不存在')
    if (!existsSync(file) || !statSync(file).isFile()) return failure(res, 404, '页面尚未就绪')
    if (!realpathSync(file).startsWith(realpathSync(dist) + sep)) return failure(res, 403, '页面不存在')
    res.writeHead(200, { 'Content-Type': mime[extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer', 'X-Frame-Options': 'DENY', 'Content-Security-Policy': "frame-ancestors 'none'; base-uri 'self'; object-src 'none'" })
    if (req.method === 'HEAD') return res.end()
    const stream = createReadStream(file); stream.on('error', () => res.destroy()); stream.pipe(res)
  }
  async function proxy(req, res, path, device) {
    if (forbidden.test(path) || !routes.some(([methods, pattern]) => methods.has(req.method) && pattern.test(path))) return failure(res, 403, '此功能请在电脑应用中操作')
    if (runtime.snapshot().mode !== 'active') return failure(res, 503, '电脑应用正在待机或恢复，请在电脑上检查')
    // A solution allows 16 MiB of images encoded as base64 plus its text.
    const limit = req.method === 'PUT' && /^\/api\/practice\/[0-9]+\/solution$/.test(path) ? 24 * 1024 * 1024 : 1024 * 1024
    const body = await readBody(req, limit, limit > 1024 * 1024 ? 90000 : 30000)
    if (!sessions.includes(device) || device.expiresAt <= now()) return failure(res, 401, '手机访问已过期或被撤销，请重新配对')
    touch(device)
    // Never forward caller-supplied cookie, auth, runtime, credential or forwarding headers.
    const headers = { host: `127.0.0.1:${gatewayPort}`, origin: 'http://127.0.0.1:5173', 'content-length': String(body.length), accept: 'application/json' }
    if (req.headers['content-type']) headers['content-type'] = req.headers['content-type']
    if (path.startsWith('/api/stocks/') && typeof req.headers['x-stock-session'] === 'string') headers['x-stock-session'] = req.headers['x-stock-session']
    const upstream = request({ hostname: '127.0.0.1', port: gatewayPort, path: req.url, method: req.method, headers, timeout: 180000 }, incoming => {
      if (incoming.statusCode >= 300 && incoming.statusCode < 400) { incoming.resume(); return failure(res, 502, '后台响应暂不可用') }
      if (path === '/api/stocks/status' && incoming.statusCode === 200) {
        let size = 0, chunks = []
        incoming.on('data', chunk => { size += chunk.length; if (size > 1024 * 1024) { upstream.destroy(); failure(res, 502, '连接状态暂不可用') } else chunks.push(chunk) })
        incoming.on('end', () => {
          try {
            const data = JSON.parse(Buffer.concat(chunks).toString('utf8'))
            const provider = value => ({ connected: value?.connected === true, configured: value?.configured === true, planEnabled: value?.planEnabled === true })
            const models = Array.isArray(data.models) ? data.models.slice(0, 100).map(m => ({ id: typeof m?.id === 'string' ? m.id.slice(0, 200) : '', name: typeof m?.name === 'string' ? m.name.slice(0, 200) : '' })) : []
            json(res, 200, { ibkr: provider(data.ibkr), gpt: provider(data.gpt), models, canAnalyze: data.canAnalyze === true, modelMessage: models.length ? '' : '模型列表暂不可用，请在电脑上检查连接' })
          } catch { failure(res, 502, '连接状态暂不可用') }
        })
      } else {
        res.writeHead(incoming.statusCode, { 'Content-Type': incoming.headers['content-type'] || 'application/json', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer' }); incoming.pipe(res)
      }
      incoming.on('error', () => { if (res.headersSent) res.destroy(); else failure(res, 502, '电脑后台暂不可用') })
    })
    const deadline = setTimeout(() => upstream.destroy(new Error('Gateway deadline')), 195000)
    upstream.on('timeout', () => upstream.destroy(new Error('Gateway timeout')))
    upstream.on('error', () => { if (res.headersSent) res.destroy(); else failure(res, 502, '电脑后台暂不可用，请稍后重试') })
    res.on('close', () => { clearTimeout(deadline); upstream.destroy() }); upstream.end(body)
  }
  async function handle(req, res) {
    if (!perimeter(req)) return failure(res, 403, '手机访问来源未获授权')
    const path = pathname(req.url)
    if (!path) return failure(res, 400, '请求路径无效')
    const device = session(req)
    if (req.method === 'GET' && path === '/api/mobile/context') {
      const state = await mode()
      return json(res, 200, { mobile: true, authenticated: !!device, ...(device ? { deviceName: device.name } : {}), mode: state.mode, online: state.mode === 'active' })
    }
    if (req.method === 'POST' && path === '/api/mobile/pair') return pairDevice(req, res)
    if (!path.startsWith('/api')) return serveStatic(req, res, path)
    if (!device) return failure(res, 401, '请使用电脑生成的配对码连接此设备')
    if (req.method === 'DELETE' && path === '/api/mobile/session') {
      const previous = sessions; sessions = sessions.filter(s => s.id !== device.id)
      try { save() } catch (error) { sessions = previous; throw error }
      setCookie(res, '', 0); return json(res, 200, { mobile: true, authenticated: false })
    }
    if (req.method === 'GET' && path === '/api/runtime/status') return json(res, 200, await mode())
    return proxy(req, res, path, device)
  }
  const server = createServer((req, res) => { handle(req, res).catch(error => failure(res, error.status || 503, ({ 413: '提交内容过大', 408: '提交超时，请重试', 400: '请求无效' })[error.status] || '手机访问暂不可用，请稍后重试')) })
  server.headersTimeout = 15000; server.requestTimeout = 120000; server.keepAliveTimeout = 5000; server.maxHeadersCount = 64
  server.on('error', () => { running = false; startupMessage = '手机入口无法启动，请在电脑上检查端口和访问设置' })
  async function stopListener() {
    running = false; pair = null
    if (server.listening) await new Promise(accept => { server.close(accept); server.closeAllConnections() })
  }
  function start() {
    if (starting) return starting
    starting = (async () => {
      const next = loadConfig()
      if (running && JSON.stringify(next) === JSON.stringify(config)) return status()
      await stopListener(); config = next; initialized = false; sessions = []
      if (!config) return status()
      try {
        initialize()
        await new Promise(accept => {
          const failed = () => { server.off('listening', ready); accept() }
          const ready = () => { server.off('error', failed); running = true; startupMessage = ''; accept() }
          server.once('error', failed); server.once('listening', ready); server.listen(mobilePort, '127.0.0.1')
        })
      } catch { startupMessage = '手机入口无法启动，请在电脑上检查访问设置' }
      return status()
    })().finally(() => { starting = null })
    return starting
  }
  async function close() { if (starting) await starting; await stopListener() }
  return { handleAdmin, start, close, status, server }
}
