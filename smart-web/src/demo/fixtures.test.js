import test from 'node:test'
import assert from 'node:assert/strict'
import { createDemoAdapter, createDemoFetch, createFixtures } from './fixtures.js'

test('demo serves synthetic mail without accessing external transports', async () => {
  const adapter = createDemoAdapter(createFixtures(1800000000000))
  const result = await adapter({ url: '/api/mails/summaries', params: { source: 'OUTLOOK' } })
  assert.equal(result.data.content.length, 1)
  assert.match(result.data.content[0].sender, /example\.com/)
  result.data.content[0].subject = 'changed'
  assert.notEqual((await adapter({url:'/api/mails/901'})).data.subject, 'changed')
})
test('unknown routes and mutations fail closed', async () => {
  const adapter = createDemoAdapter()
  for (const config of [{url:'/api/credentials'}, {url:'/api/tasks',method:'post'}, {url:'/api/runtime/shutdown',method:'post'}, {url:'https://unknown.example.com/'}]) {
    await assert.rejects(adapter(config), /unavailable in the read-only demo/)
  }
})

test('demo starts the local workspace after the mobile-shell change without a live connection', async () => {
  const fetch = createDemoFetch()
  assert.deepEqual(await (await fetch('/api/mobile/context')).json(), { mobile: false })
  assert.equal((await (await fetch('/api/runtime/status')).json()).mode, 'active')
  for (const [input, options] of [
    ['/api/mobile/context', { method: 'POST' }],
    ['/api/runtime/status', { method: 'POST' }],
    ['/api/mobile/pair'], ['/api/stocks/holdings'], ['https://unknown.example.com/api/runtime/status'],
  ]) await assert.rejects(fetch(input, options), /Network requests are disabled/)
})
