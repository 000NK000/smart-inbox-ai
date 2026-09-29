import test from 'node:test'
import assert from 'node:assert/strict'
import { effectScope, nextTick, ref } from 'vue'
import {
  chinesePhilosophyQuotes, englishPhilosophyQuotes, pickPhilosophyQuote, usePhilosophyQuote
} from './philosophyQuotes.js'

function memoryStorage(initial = {}) {
  const values = new Map(Object.entries(initial))
  return { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value) }
}

test('English quotations have original-language attribution and primary-text references; Nietzsche remains excluded', () => {
  assert.equal(chinesePhilosophyQuotes.length, 15)
  assert.ok(englishPhilosophyQuotes.length >= 5)
  for (const quote of englishPhilosophyQuotes) {
    assert.doesNotMatch(quote.text + quote.author + quote.work, /\p{Script=Han}/u)
    assert.match(quote.source, /^https:\/\/www\.gutenberg\.org\//)
    assert.ok(quote.work.length > 10)
  }
  for (const quote of [...chinesePhilosophyQuotes, ...englishPhilosophyQuotes]) {
    assert.doesNotMatch(quote.author, /Nietzsche|尼采/i)
  }
})

test('refreshes never immediately repeat the last quotation in either language', () => {
  for (const locale of ['zh-CN', 'en-US']) {
    const storage = memoryStorage()
    let previous
    for (let n = 0; n < 80; n++) {
      const quote = pickPhilosophyQuote(locale, { storage, random: () => (n % 20) / 20 })
      assert.ok(quote?.text)
      assert.notEqual(quote, previous)
      previous = quote
    }
  }
})

test('a saved Chinese index survives upgrade, and English selections do not overwrite it', () => {
  const storage = memoryStorage({ 'smart-inbox.last-philosophy-quote': '0' })
  pickPhilosophyQuote('en-US', { storage, random: () => 0 })
  assert.equal(storage.getItem('smart-inbox.last-philosophy-quote'), '0')
  assert.equal(pickPhilosophyQuote('zh-CN', { storage, random: () => 0 }), chinesePhilosophyQuotes[1])
  assert.equal(storage.getItem('smart-inbox.last-philosophy-quote.en-US'), '0')
})

test('corrupt or out-of-range saved selections do not produce a missing quotation', () => {
  for (const previous of ['bad', '-5', '999', '']) {
    const storage = memoryStorage({ 'smart-inbox.last-philosophy-quote.en-US': previous })
    const quote = pickPhilosophyQuote('en-US', { storage, random: () => 0.999 })
    assert.ok(englishPhilosophyQuotes.includes(quote))
  }
})

test('a disabled local storage still displays the requested language', () => {
  const storage = { getItem() { throw new Error('denied') }, setItem() { throw new Error('denied') } }
  assert.ok(englishPhilosophyQuotes.includes(pickPhilosophyQuote('en-US', { storage })))
  assert.ok(chinesePhilosophyQuotes.includes(pickPhilosophyQuote('zh-CN', { storage })))
})

test('switching UI language updates both text and author without a page reload', async () => {
  const scope = effectScope()
  const locale = ref('zh-CN')
  const storage = memoryStorage()
  let quote
  try {
    scope.run(() => { quote = usePhilosophyQuote(locale, { storage, random: () => 0 }) })
    assert.equal(quote.value, chinesePhilosophyQuotes[0])
    locale.value = 'en-US'
    await nextTick()
    assert.equal(quote.value, englishPhilosophyQuotes[0])
    assert.equal(quote.value.author, 'Bertrand Russell')
    locale.value = 'zh-CN'
    await nextTick()
    assert.equal(quote.value, chinesePhilosophyQuotes[1])
  } finally { scope.stop() }
})

test('opening the dashboard again selects a fresh quote with the saved English preference', () => {
  const locale = ref('en-US')
  const storage = memoryStorage()
  const open = () => {
    const scope = effectScope()
    const quote = scope.run(() => usePhilosophyQuote(locale, { storage, random: () => 0 })).value
    scope.stop()
    return quote
  }
  const first = open()
  const second = open()
  assert.notEqual(first, second)
  assert.ok(englishPhilosophyQuotes.includes(second))
})
