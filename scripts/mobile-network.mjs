import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { existsSync } from 'node:fs'
import { mkdir, writeFile, rename, chmod } from 'node:fs/promises'
import { join } from 'node:path'
import { randomBytes } from 'node:crypto'

const exec = promisify(execFile)
const target = 'http://127.0.0.1:5174'
const executable = process.platform === 'win32'
  ? join(process.env.ProgramFiles || 'C:\\Program Files', 'Tailscale', 'tailscale.exe') : 'tailscale'
let cachedInspection
let setupInProgress = false

async function run(args, options = {}) {
  try {
    return await exec(executable, args, { windowsHide: true, timeout: 20000, maxBuffer: 1024 * 1024, ...options })
  } catch (error) {
    // Logged-out status can exit nonzero while still returning valid JSON.
    if (args[0] === 'status' && error.stdout) return { stdout: error.stdout, stderr: '' }
    throw error
  }
}

function installed() { return process.platform !== 'win32' || existsSync(executable) }
function parse(text) { try { return JSON.parse(text) } catch { throw new Error('无法读取 Tailscale 状态，请确认它正在运行。') } }

export function mobileNetworkIdentity(status) {
  if (status?.BackendState !== 'Running' || !status.Self?.Online) return null
  const hostname = String(status.Self.DNSName || '').replace(/\.$/, '').toLowerCase()
  if (!/^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.[a-z0-9-]+\.ts\.net$/.test(hostname)) {
    throw new Error('Tailscale 尚未提供私人 HTTPS 地址，请先完成登录并启用 MagicDNS。')
  }
  const profile = status.User?.[String(status.Self.UserID)]
  const login = profile?.LoginName
  if (typeof login !== 'string' || !login || login.length > 320 || /[\r\n\x00-\x1f]/.test(login)) {
    throw new Error('无法确认当前 Tailscale 用户，请在电脑端重新登录。')
  }
  return { hostname, origin: `https://${hostname}`, allowedLogin: login }
}

// Never reset another application's Serve config or turn on public Funnel.
export function assertMobileServeAvailable(config, hostname) {
  const key = `${hostname}:443`
  if (config?.AllowFunnel?.[key] === true) throw new Error('此地址已开启公开 Funnel。请先在 Tailscale 关闭该地址的 Funnel，再设置私人访问。')
  const port = config?.TCP?.['443']
  if (port && (port.HTTPS !== true || port.TCPForward || port.TerminateTLS)) {
    throw new Error('Tailscale 的 443 端口已被其他服务使用，没有修改它。')
  }
  const sites = Object.entries(config?.Web || {}).filter(([address]) => address.endsWith(':443'))
  for (const [address, site] of sites) {
    const handlers = Object.entries(site?.Handlers || {})
    if (address !== key || handlers.length !== 1 || handlers[0][0] !== '/' || handlers[0][1]?.Proxy !== target) {
      throw new Error('Tailscale 的 HTTPS 地址已配置其他应用，没有覆盖它。')
    }
  }
}

export function mobileApprovalUrl(output) {
  const matches = String(output || '').match(/https:\/\/login\.tailscale\.com\/[^\s<>"']+/g) || []
  for (const match of matches) {
    try { const url = new URL(match); if (url.origin === 'https://login.tailscale.com' && !url.username && !url.password) return url.href } catch { /* ignore invalid text */ }
  }
  return undefined
}

export async function inspectMobileNetwork() {
  if (cachedInspection && cachedInspection.until > Date.now()) return cachedInspection.value
  let value
  if (!installed()) value = { installed: false, connected: false, needsLogin: true, message: '请先安装电脑端 Tailscale。' }
  else {
    try {
      const status = parse((await run(['status', '--json'], { timeout: 6000 })).stdout)
      const identity = mobileNetworkIdentity(status)
      value = { installed: true, connected: !!identity, needsLogin: !identity,
        message: identity ? '电脑已连接私人网络，可以完成网络设置。' : '请先在电脑端 Tailscale 登录，与 iPhone 使用同一账号。' }
    } catch { value = { installed: true, connected: false, needsLogin: true, message: 'Tailscale 尚未就绪，请打开它并完成登录。' } }
  }
  cachedInspection = { until: Date.now() + 5000, value }
  return value
}

async function saveConfiguration(root, identity) {
  const directory = join(root, '.smart-inbox', 'mobile')
  await mkdir(directory, { recursive: true, mode: 0o700 })
  if (process.platform === 'win32') {
    const { stdout } = await exec('whoami.exe', ['/user', '/fo', 'csv', '/nh'], { windowsHide: true, timeout: 5000 })
    const sid = stdout.match(/S-1-5-[\d-]+/)?.[0]
    if (!sid) throw new Error('无法设置本机手机连接配置的文件权限。')
    await exec('icacls.exe', [directory, '/inheritance:r', '/grant:r', `*${sid}:(OI)(CI)F`, '*S-1-5-18:(OI)(CI)F'], { windowsHide: true, timeout: 5000 })
  } else await chmod(directory, 0o700)
  const file = join(directory, 'config.json')
  const temporary = join(directory, `config-${randomBytes(6).toString('hex')}.tmp`)
  await writeFile(temporary, JSON.stringify({ enabled: true, origin: identity.origin, allowedLogin: identity.allowedLogin }), { mode: 0o600 })
  await rename(temporary, file)
}

export async function configureMobileNetwork(root, dependencies = {}) {
  if (setupInProgress) return { configured: false, message: '网络设置正在进行，请稍后刷新。' }
  setupInProgress = true
  const invoke = dependencies.run || run
  try {
    if (!dependencies.run && !installed()) return { configured: false, needsLogin: true, message: '请先安装电脑端 Tailscale。' }
    const identity = mobileNetworkIdentity(parse((await invoke(['status', '--json'])).stdout))
    if (!identity) return { configured: false, needsLogin: true, message: '请先在电脑端 Tailscale 登录，与 iPhone 使用同一账号。' }
    const before = parse((await invoke(['serve', 'status', '--json'])).stdout)
    assertMobileServeAvailable(before, identity.hostname)
    let setupOutput
    try {
      setupOutput = await invoke(['serve', '--bg', '--https=443', target])
    } catch (error) {
      const approvalUrl = mobileApprovalUrl(`${error.stdout || ''}\n${error.stderr || ''}`)
      return { configured: false, ...(approvalUrl ? { approvalUrl } : {}), message: approvalUrl
        ? '请打开 Tailscale 官方设置页面启用 HTTPS，然后再次点击完成网络设置。'
        : '私人 HTTPS 设置未完成，请确认 Tailscale 已登录并允许使用 Serve，然后重试。' }
    }
    const after = parse((await invoke(['serve', 'status', '--json'])).stdout)
    assertMobileServeAvailable(after, identity.hostname)
    if (after?.TCP?.['443']?.HTTPS !== true || after?.Web?.[`${identity.hostname}:443`]?.Handlers?.['/']?.Proxy !== target) {
      const approvalUrl = mobileApprovalUrl(`${setupOutput?.stdout || ''}\n${setupOutput?.stderr || ''}`)
      return { configured: false, ...(approvalUrl ? { approvalUrl } : {}), message: '私人 HTTPS 入口尚未生效，请完成 Tailscale HTTPS 设置后重试。' }
    }
    await (dependencies.save || saveConfiguration)(root, identity)
    cachedInspection = undefined
    return { configured: true, origin: identity.origin, message: '私人网络入口已准备好，可以生成手机配对码。' }
  } catch (error) {
    // Never expose CLI output (which can include identity or authentication URLs) as an error.
    const known = error instanceof Error && /^(Tailscale|此地址|无法|私人)/.test(error.message)
    return { configured: false, message: known ? error.message : '网络设置未完成，请确认 Tailscale 已就绪后重试。' }
  } finally { setupInProgress = false }
}
