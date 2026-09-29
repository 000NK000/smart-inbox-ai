import { createServer, request } from 'node:http'
import { createReadStream, existsSync, statSync } from 'node:fs'
import { resolve, sep, extname, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRuntimeControl } from './runtime-control.mjs'

const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.ico': 'image/x-icon', '.woff2': 'font/woff2' }
export function createDesktopServer(root, options = {}) {
  const dist = resolve(root, 'smart-web/dist')
  const runtime = options.runtime || createRuntimeControl(root)
  const gatewayPort = options.gatewayPort || 8080
  return createServer((req, res) => {
    if (!['127.0.0.1:5173', 'localhost:5173'].includes(req.headers.host) || !['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(req.socket.remoteAddress)) {
      res.writeHead(403); res.end('Local access only'); return
    }
    runtime.middleware(req, res, () => {
      if (req.url.startsWith('/api/')) {
        const upstream = request({ hostname: '127.0.0.1', port: gatewayPort, path: req.url, method: req.method, headers: { ...req.headers, host: `127.0.0.1:${gatewayPort}` }, timeout: 180000 }, incoming => {
          res.writeHead(incoming.statusCode, incoming.headers); incoming.pipe(res)
        })
        upstream.on('timeout', () => upstream.destroy(new Error('Gateway timeout')))
        upstream.on('error', () => { if (!res.headersSent) res.writeHead(502, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ message: '后台尚未就绪，请稍后重试。' })) })
        res.on('close', () => upstream.destroy())
        req.pipe(upstream); return
      }
      if (!['GET', 'HEAD'].includes(req.method)) { res.writeHead(405); res.end(); return }
      let pathname
      try { pathname = decodeURIComponent(req.url.split('?')[0]) } catch { res.writeHead(400); res.end(); return }
      if (pathname.includes('\0') || pathname.includes('\\')) { res.writeHead(400); res.end(); return }
      let file = resolve(dist, '.' + pathname)
      if (file !== dist && !file.startsWith(dist + sep)) { res.writeHead(403); res.end(); return }
      if (!existsSync(file) || !statSync(file).isFile()) {
        if (extname(pathname)) { res.writeHead(404); res.end(); return }
        file = resolve(dist, 'index.html')
      }
      if (!existsSync(file)) { res.writeHead(503); res.end('Build the frontend first.'); return }
      res.writeHead(200, { 'Content-Type': mime[extname(file)] || 'application/octet-stream', 'Cache-Control': extname(file) === '.html' ? 'no-store' : 'no-cache', 'X-Content-Type-Options': 'nosniff' })
      if (req.method === 'HEAD') { res.end(); return }
      const stream = createReadStream(file)
      stream.on('error', () => res.destroy()); stream.pipe(res)
    })
  })
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
  const server = createDesktopServer(root)
  server.on('error', error => { console.error(error.message); process.exitCode = 1 })
  server.listen(5173, '127.0.0.1', () => console.log('Smart Inbox desktop server ready: http://127.0.0.1:5173'))
}
