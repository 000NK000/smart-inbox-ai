import test from 'node:test'
import assert from 'node:assert/strict'
import { newsOfficialLink } from './newsOfficialLink.js'

test('links to the matching publisher, and labels index links as homepages', () => {
  assert.deepEqual(newsOfficialLink({ source: 'CNN', url: 'https://edition.cnn.com/world/article' }), { url: 'https://edition.cnn.com/world/article', article: true })
  assert.deepEqual(newsOfficialLink({ source: 'CNN', url: 'https://news.google.com/rss/articles/123' }), { url: 'https://www.cnn.com/', article: false })
  assert.equal(newsOfficialLink({ source: 'ABC News', url: 'https://abcnews.com/International/story' }).article, true)
})
test('never exposes injected or unrelated URLs as official article links', () => {
  for (const url of ['javascript:alert(1)', 'https://cnn.com.evil.test/a', 'https://mailbox@example.com/a', 'https://cnn.com:8080/a', 'https://127.0.0.1', 'https://npr.org/a']) {
    assert.deepEqual(newsOfficialLink({ source: 'CNN', url }), { url: 'https://www.cnn.com/', article: false })
  }
  assert.equal(newsOfficialLink({ source: 'unknown', url: 'https://example.com' }).url, '')
})
