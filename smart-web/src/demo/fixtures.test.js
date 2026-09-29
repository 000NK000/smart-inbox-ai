import test from 'node:test'
import assert from 'node:assert/strict'
import { createDemoAdapter, createFixtures } from './fixtures.js'

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
