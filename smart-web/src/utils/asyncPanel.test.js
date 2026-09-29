import test from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'

const dom = new JSDOM('<div id="root"></div>', { url: 'http://localhost/' })
Object.assign(globalThis, {
  window: dom.window, document: dom.window.document,
  Element: dom.window.Element, SVGElement: dom.window.SVGElement,
  localStorage: dom.window.localStorage,
})
const Vue = await import('vue')
const { createAsyncPanel } = await import('./asyncPanel.js')
const { setLocale } = await import('../i18n/index.js')
const flush = async () => { for (let i = 0; i < 12; i++) { await Promise.resolve(); await Vue.nextTick() } }
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const loadedPanel = Vue.defineComponent({
  props: ['title'], emits: ['ready'],
  setup(props, { emit, slots }) {
    return () => Vue.h('article', { class: 'loaded-panel', onClick: () => emit('ready') }, [props.title, slots.default?.()])
  },
})
function mount(t, loader, options = {}, props = {}, slots = {}) {
  let reloads = 0
  const errors = []
  const component = createAsyncPanel(loader, { delay: 10, timeout: 80, reload: () => { reloads++ }, ...options })
  const app = Vue.createApp({ render: () => Vue.h(component, props, slots) })
  app.config.errorHandler = error => errors.push(error.message)
  app.mount('#root')
  t.after(() => { app.unmount(); setLocale('zh-CN') })
  return { errors, reloads: () => reloads }
}

test('pending panel shows a loading state, then forwards props, events and slots to the loaded panel', async t => {
  const pending = deferred()
  let events = 0
  const state = mount(t, () => pending.promise, {}, { title: 'Weather', onReady: () => events++ }, { default: () => 'Forecast' })
  await sleep(20); await flush()
  assert.equal(document.querySelector('[role="status"]').textContent, '正在加载页面…')
  pending.resolve(loadedPanel); await flush()
  assert.equal(document.querySelector('.loaded-panel').textContent, 'WeatherForecast')
  document.querySelector('.loaded-panel').click()
  assert.equal(events, 1)
  assert.equal(document.querySelector('.async-panel-loading'), null)
  assert.deepEqual(state.errors, [])
})

test('failed import displays recovery controls and retries only when clicked', async t => {
  let calls = 0
  const state = mount(t, () => ++calls === 1 ? Promise.reject(new TypeError('Failed to fetch dynamically imported module')) : Promise.resolve(loadedPanel), {}, { title: 'Recovered' })
  await flush()
  assert.match(document.querySelector('[role="alert"]').textContent, /页面暂时无法加载/)
  assert.equal(calls, 1)
  assert.equal(state.reloads(), 0)
  document.querySelector('.async-panel-error button').click(); await flush()
  assert.equal(calls, 2)
  assert.equal(document.querySelector('.loaded-panel').textContent, 'Recovered')
  assert.equal(document.querySelector('.async-panel-error'), null)
  assert.equal(state.reloads(), 0)
})

test('hung import times out visibly and can be retried without waiting for its old request', async t => {
  const pending = deferred()
  let calls = 0
  const state = mount(t, () => ++calls === 1 ? pending.promise : Promise.resolve(loadedPanel), { timeout: 25 })
  await sleep(40); await flush()
  assert.ok(document.querySelector('.async-panel-error'))
  assert.match(state.errors[0], /timed out/)
  document.querySelector('.async-panel-error button').click(); await flush()
  assert.ok(document.querySelector('.loaded-panel'))
  pending.resolve({ render: () => Vue.h('div', { class: 'obsolete-panel' }, 'Old request') }); await flush()
  assert.ok(document.querySelector('.loaded-panel'))
  assert.equal(document.querySelector('.obsolete-panel'), null)
})

test('a late successful import can replace the timeout message without reloading', async t => {
  const pending = deferred()
  const state = mount(t, () => pending.promise, { timeout: 25 })
  await sleep(40); await flush()
  assert.ok(document.querySelector('.async-panel-error'))
  pending.resolve(loadedPanel); await flush()
  assert.ok(document.querySelector('.loaded-panel'))
  assert.equal(document.querySelector('.async-panel-error'), null)
  assert.equal(state.reloads(), 0)
})

test('error controls update locale and explain the user-initiated reload before it is clicked', async t => {
  const state = mount(t, () => Promise.reject(new Error('Synthetic failure')))
  await flush()
  setLocale('en-US'); await flush()
  assert.match(document.querySelector('[role="alert"]').textContent, /This page could not be loaded/)
  assert.match(document.querySelector('[role="alert"]').textContent, /Unsaved input will be lost/)
  const buttons = document.querySelectorAll('.async-panel-error button')
  assert.equal(buttons[0].textContent, 'Try again')
  assert.equal(buttons[1].textContent, 'Refresh and return to the main menu')
  assert.equal(state.reloads(), 0)
  buttons[1].click()
  assert.equal(state.reloads(), 1)
})

test('recovery is per panel instance and leaves unrelated draft inputs intact', async t => {
  let calls = 0, reloads = 0
  const component = createAsyncPanel(() => ++calls === 1 ? Promise.reject(new Error('First instance failed')) : Promise.resolve(loadedPanel), { delay: 0, timeout: 80, reload: () => reloads++ })
  const app = Vue.createApp({ render: () => Vue.h('div', [Vue.h('input', { class: 'draft' }), Vue.h(component, { title: 'First' }), Vue.h(component, { title: 'Second' })]) })
  app.config.errorHandler = () => {}
  app.mount('#root')
  t.after(() => app.unmount())
  document.querySelector('.draft').value = 'Unsaved input'
  await flush()
  const secondInstance = document.querySelector('.loaded-panel')
  assert.equal(secondInstance.textContent, 'Second')
  document.querySelector('.async-panel-error button').click(); await flush()
  assert.equal(document.querySelectorAll('.loaded-panel').length, 2)
  assert.equal(document.querySelectorAll('.loaded-panel')[1], secondInstance)
  assert.equal(document.querySelector('.draft').value, 'Unsaved input')
  assert.equal(reloads, 0)
})
