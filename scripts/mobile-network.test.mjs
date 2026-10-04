import test from 'node:test'
import assert from 'node:assert/strict'
import { mobileNetworkIdentity, assertMobileServeAvailable, mobileApprovalUrl, configureMobileNetwork } from './mobile-network.mjs'
const identity = { BackendState: 'Running', Self: { Online: true, DNSName: 'pc.test-tail.ts.net.', UserID: 7 }, User: { 7: { LoginName: 'test@example.test' } } }
const serve = { TCP: { 443: { HTTPS: true } }, Web: { 'pc.test-tail.ts.net:443': { Handlers: { '/': { Proxy: 'http://127.0.0.1:5174' } } } } }

test('derive private origin only from a signed-in untagged Tailscale identity', () => {
  assert.equal(mobileNetworkIdentity(identity).origin, 'https://pc.test-tail.ts.net')
  assert.equal(mobileNetworkIdentity({ BackendState: 'NeedsLogin' }), null)
  assert.throws(() => mobileNetworkIdentity({ ...identity, User: {} }))
  assert.throws(() => mobileNetworkIdentity({ ...identity, Self: { ...identity.Self, DNSName: 'evil.example' } }))
})
test('preserve existing services and reject public Funnel', () => {
  assert.doesNotThrow(() => assertMobileServeAvailable({}, 'pc.test-tail.ts.net'))
  assert.doesNotThrow(() => assertMobileServeAvailable(serve, 'pc.test-tail.ts.net'))
  assert.throws(() => assertMobileServeAvailable({ ...serve, AllowFunnel: { 'pc.test-tail.ts.net:443': true } }, 'pc.test-tail.ts.net'))
  assert.throws(() => assertMobileServeAvailable({ TCP: { 443: { TCPForward: '127.0.0.1:22' } } }, 'pc.test-tail.ts.net'))
  assert.throws(() => assertMobileServeAvailable({ Web: { 'pc.test-tail.ts.net:443': { Handlers: { '/': { Proxy: 'http://127.0.0.1:9000' } } } } }, 'pc.test-tail.ts.net'))
  assert.throws(() => assertMobileServeAvailable({ Web: { 'other.test-tail.ts.net:443': serve.Web['pc.test-tail.ts.net:443'] } }, 'pc.test-tail.ts.net'))
})
test('only return official login URLs for manual HTTPS consent', () => {
  assert.equal(mobileApprovalUrl('visit https://evil.test/path'), undefined)
  assert.equal(mobileApprovalUrl('visit https://login.tailscale.com.evil.test/path'), undefined)
  assert.equal(mobileApprovalUrl('Enable at https://login.tailscale.com/f/serve?node=test'), 'https://login.tailscale.com/f/serve?node=test')
})
test('logged-out setup makes no network or configuration changes', async () => {
  const calls = []
  const result = await configureMobileNetwork('unused', { run: async args => { calls.push(args); return { stdout: '{"BackendState":"NeedsLogin"}' } }, save: async () => assert.fail('must not save') })
  assert.equal(result.needsLogin, true)
  assert.deepEqual(calls, [['status', '--json']])
})
test('setup verifies Serve before persisting configuration', async () => {
  const calls = []; let saved
  const result = await configureMobileNetwork('unused', { run: async args => {
    calls.push(args)
    return { stdout: JSON.stringify(args[0] === 'status' ? identity : calls.length === 2 ? {} : serve) }
  }, save: async (_root, value) => { saved = value } })
  assert.equal(result.configured, true)
  assert.equal(saved.allowedLogin, 'test@example.test')
  assert.deepEqual(calls[2], ['serve', '--bg', '--https=443', 'http://127.0.0.1:5174'])
})
test('failed HTTPS consent is bounded and never persists a usable config', async () => {
  const result = await configureMobileNetwork('unused', { run: async args => {
    if (args[0] === 'status') return { stdout: JSON.stringify(identity) }
    if (args[1] === 'status') return { stdout: '{}' }
    throw Object.assign(new Error('private output'), { stderr: 'approve https://login.tailscale.com/f/serve' })
  }, save: async () => assert.fail('must not save') })
  assert.equal(result.configured, false)
  assert.equal(result.approvalUrl, 'https://login.tailscale.com/f/serve')
  assert.ok(!JSON.stringify(result).includes('private output'))
})

test('successful CLI exit without active Serve still exposes the official consent step', async () => {
  const result = await configureMobileNetwork('unused', { run: async args => {
    if (args[0] === 'status') return { stdout: JSON.stringify(identity) }
    if (args[1] === 'status') return { stdout: '{}' }
    return { stdout: 'Enable HTTPS at https://login.tailscale.com/f/serve' }
  }, save: async () => assert.fail('must not save unverified config') })
  assert.equal(result.configured, false)
  assert.equal(result.approvalUrl, 'https://login.tailscale.com/f/serve')
})
