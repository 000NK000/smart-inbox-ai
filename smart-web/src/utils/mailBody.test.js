import { test } from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'
import { renderMailBody } from './mailBody.js'
const win = new JSDOM('').window

test('retains shipment layout and image while folding only legal footer', () => {
  const html = '<table><tr><td><h2>Arriving today 5–10 p.m.</h2><img src="https://images.example.com/kettle.jpg"><a href="https://amazon.ca/track">Track package</a><p>Total $45.19</p></td></tr><tr><td><p>©2026 Amazon.com Inc. All rights reserved.</p><p><a href="https://amazon.ca/privacy">Privacy Policy</a></p></td></tr></table>'
  const compact = renderMailBody(html, '', true, win)
  assert.match(compact.document, /Arriving today/)
  assert.match(compact.document, /kettle.jpg/)
  assert.match(compact.document, /Total \$45.19/)
  assert.match(compact.document, /Track package/)
  assert.doesNotMatch(compact.document, /©2026|Privacy Policy/)
  assert.match(renderMailBody(html, '', false, win).document, /©2026/)
})
test('removes active content, local URLs and tracking pixels', () => {
  const result = renderMailBody('<script>alert(1)</script><iframe src="https://evil.test"></iframe><form action="https://evil.test"><input></form><img src="https://evil.test/x" onerror="alert(1)" width="1"><img src="http://127.0.0.1:8080/api/secret"><a href="javascript:alert(1)">link</a><meta http-equiv="refresh" content="0;url=https://evil.test"><base href="http://127.0.0.1">', '', false, win).document
  assert.doesNotMatch(result, /<script|<iframe|<form|<input|onerror|javascript:|127.0.0.1|http-equiv="refresh"|<base/)
  assert.match(result, /Content-Security-Policy/)
})
test('plain text fallback cannot introduce HTML', () => {
  const result = renderMailBody('', '<script>danger</script>\n中文 English', true, win).document
  assert.match(result, /&lt;script&gt;/)
  assert.match(result, /中文 English/)
})
