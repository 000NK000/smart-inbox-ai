import { randomBytes, timingSafeEqual } from 'node:crypto'
import { mkdirSync, readFileSync, renameSync, writeFileSync, appendFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { spawn } from 'node:child_process'

// This controller lives in the small web process, outside the Java services it stops.
export function createRuntimeControl(root, execute = runPowerShell) {
  const directory = join(root, '.smart-inbox', 'runtime')
  const file = join(directory, 'state.json')
  mkdirSync(directory, { recursive: true })
  const token = randomBytes(32).toString('hex')
  let state = { mode: 'active', message: '正常运行' }
  try { state = JSON.parse(readFileSync(file, 'utf8')) } catch { /* first launch */ }
  if (['entering', 'resuming'].includes(state.mode)) {
    state = { mode: 'error', message: '上次切换被中断，请点击恢复运行。' }
  }
  let busy = false
  function save(mode, message) {
    state = { mode, message, updatedAt: new Date().toISOString() }
    writeFileSync(file + '.tmp', JSON.stringify(state))
    renameSync(file + '.tmp', file)
  }
  const snapshot = () => ({ ...state, token })
  function transition(action) {
    if (busy) return false
    if ((action === 'standby' && state.mode === 'standby') || (action === 'resume' && state.mode === 'active')) return true
    busy = true
    save(action === 'standby' ? 'entering' : 'resuming', action === 'standby' ? '正在保存数据并释放资源…' : '正在恢复后台服务…')
    Promise.resolve().then(() => execute(root, action, message => save(state.mode, message)))
      .then(() => save(action === 'standby' ? 'standby' : 'active', action === 'standby' ? '已停止后台服务并卸载本项目 AI 模型' : '正常运行'))
      .catch(error => {
        appendFileSync(join(directory, 'errors.log'), `${new Date().toISOString()} ${error.stack || error}\n`)
        const portFailure = /ports are not available|bind:.*(?:forbidden|access permissions|already in use)/i.test(String(error))
        save('error', portFailure
          ? '后台端口被占用或被 Windows 保留，邮件队列无法启动。需要调整端口配置后恢复运行；详情见 .smart-inbox/runtime/errors.log。'
          : '切换未完成。请点击恢复运行重试，详情见 .smart-inbox/runtime/errors.log。')
      }).finally(() => { busy = false })
    return true
  }
  function json(res, code, value) {
    res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
    res.end(JSON.stringify(value))
  }
  function middleware(req, res, next) {
    const path = req.url?.split('?')[0]
    if (!path?.startsWith('/api/runtime')) {
      if (path?.startsWith('/api/') && state.mode !== 'active') return json(res, 503, { message: '项目正在待机或恢复', standby: true })
      return next()
    }
    // No CORS; protect against DNS rebinding, cross-site POSTs and iframe forms.
    const host = req.headers.host
    const local = ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(req.socket.remoteAddress)
    if (!local || !['127.0.0.1:5173', 'localhost:5173'].includes(host)) return json(res, 403, { message: '仅支持本机访问' })
    if (req.headers.origin && req.headers.origin !== `http://${host}`) return json(res, 403, { message: '来源不匹配' })
    if (req.headers['sec-fetch-site'] === 'cross-site') return json(res, 403, { message: '禁止跨站请求' })
    if (req.method === 'GET' && path === '/api/runtime/status') return json(res, 200, snapshot())
    if (req.method !== 'POST' || !['/api/runtime/standby', '/api/runtime/resume'].includes(path)) return json(res, 404, { message: '接口不存在' })
    const supplied = Buffer.from(String(req.headers['x-runtime-token'] || ''))
    const expected = Buffer.from(token)
    if (supplied.length !== expected.length || !timingSafeEqual(supplied, expected)) return json(res, 403, { message: '请刷新页面后重试' })
    if (!transition(path.endsWith('/standby') ? 'standby' : 'resume')) return json(res, 409, snapshot())
    return json(res, 202, snapshot())
  }
  return { middleware, snapshot, transition }
}

function runPowerShell(root, action, progress) {
  return new Promise((accept, reject) => {
    const child = spawn('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', join(root, 'scripts', 'runtime-action.ps1'), '-Action', action], {
      cwd: root, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe']
    })
    let output = ''
    const record = data => {
      const text = data.toString(); output = (output + text).slice(-12000)
      for (const line of text.split(/\r?\n/)) if (line.startsWith('PROGRESS:')) progress(line.slice(9).trim())
    }
    child.stdout.on('data', record); child.stderr.on('data', record)
    child.on('error', reject)
    child.on('exit', code => code === 0 ? accept() : reject(new Error(`Runtime action ${action} failed (${code}): ${output}`)))
  })
}

export function runtimePlugin(root) {
  return { name: 'smart-inbox-runtime', configureServer(server) { server.middlewares.use(createRuntimeControl(resolve(root)).middleware) } }
}
