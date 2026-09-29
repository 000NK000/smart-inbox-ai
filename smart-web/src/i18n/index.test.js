import test from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'

test('locale is local, persistent, safe in blocked storage, and never translates unmarked content', async () => {
  const dom = new JSDOM('', { url: 'http://localhost/' })
  globalThis.window = dom.window
  globalThis.document = dom.window.document
  globalThis.localStorage = dom.window.localStorage
  const api = await import('./index.js?initial')
  assert.equal(api.locale.value, 'zh-CN')
  assert.equal(api.t('邮件中心'), '邮件中心')
  api.setLocale('en-US')
  assert.equal(api.t('邮件中心'), 'Mail center')
  assert.equal(document.documentElement.lang, 'en-US')
  assert.equal(localStorage.getItem(api.LANGUAGE_STORAGE_KEY), 'en-US')
  assert.equal(api.t('中文原始电影名称'), '中文原始电影名称')
  assert.equal(api.t('Value: {count}', { count: 3 }), 'Value: 3')
  assert.equal(api.t('Value: {count}'), 'Value: {count}')
  const reloaded = await import('./index.js?reloaded')
  assert.equal(reloaded.locale.value, 'en-US')
  reloaded.setLocale('invalid')
  assert.equal(reloaded.locale.value, 'en-US')
  window.dispatchEvent(new dom.window.StorageEvent('storage', { key: api.LANGUAGE_STORAGE_KEY, newValue: 'zh-CN' }))
  assert.equal(reloaded.locale.value, 'zh-CN')
  assert.equal(document.documentElement.lang, 'zh-CN')
  globalThis.localStorage = { getItem() { throw new Error('blocked') }, setItem() { throw new Error('blocked') } }
  const blocked = await import('./index.js?blocked')
  assert.equal(blocked.locale.value, 'zh-CN')
  assert.doesNotThrow(() => blocked.setLocale('en-US'))
  assert.equal(blocked.locale.value, 'en-US')
  dom.window.close()
})
