import test from 'node:test'
import assert from 'node:assert/strict'
import { nextDayStart, elapsedSinceSnapshot, rangeDays, chartBuckets, clockTime } from './focusTime.js'

test('elapsed display is bounded by server lease and midnight even after sleep or clock changes', () => {
  const overview = { serverNow: 100000, active: { leaseUntil: 145000 } }
  assert.equal(elapsedSinceSnapshot(overview, 500, 2500, 200000), 2000)
  assert.equal(elapsedSinceSnapshot(overview, 500, 3600500, 200000), 45000)
  assert.equal(elapsedSinceSnapshot(overview, 500, 3600500, 110000), 10000)
  assert.equal(elapsedSinceSnapshot(overview, 500, 0, 200000), 0)
  assert.equal(elapsedSinceSnapshot({ ...overview, active: null }, 500, 2500, 200000), 0)
  assert.equal(elapsedSinceSnapshot({ ...overview, active: { leaseUntil: 99000 } }, 500, 2500, 200000), 0)
})

test('day boundary follows New York daylight-saving spring and fall days', () => {
  assert.equal(nextDayStart(Date.parse('2026-03-08T05:00:00Z'), 'America/New_York'), Date.parse('2026-03-09T04:00:00Z'))
  assert.equal(nextDayStart(Date.parse('2026-11-01T04:00:00Z'), 'America/New_York'), Date.parse('2026-11-02T05:00:00Z'))
  assert.equal(nextDayStart(Date.parse('2026-10-02T03:59:59Z'), 'America/New_York'), Date.parse('2026-10-02T04:00:00Z'))
})

test('history ranges use calendar months and chart buckets preserve both category totals', () => {
  const days = Array.from({ length: 100 }, (_, index) => {
    const date = new Date('2026-03-31T12:00:00Z'); date.setUTCDate(date.getUTCDate() - 99 + index)
    return { date: date.toISOString().slice(0, 10), totals: { EFFECTIVE: index, INEFFECTIVE: index * 2 } }
  })
  assert.equal(rangeDays(days, '2026-03-31', 'week').length, 7)
  assert.equal(rangeDays(days, '2026-03-31', 'month')[0].date, '2026-02-28')
  const quarter = rangeDays(days, '2026-03-31', 'quarter')
  const buckets = chartBuckets(quarter)
  assert.equal(buckets[0].start, days[0].date); assert.equal(buckets.at(-1).end, '2026-03-31')
  assert.equal(buckets.reduce((sum, x) => sum + x.totals.EFFECTIVE, 0), 4950)
  assert.equal(buckets.reduce((sum, x) => sum + x.totals.INEFFECTIVE, 0), 9900)
  assert.equal(clockTime(3661000), '01:01:01'); assert.equal(clockTime(-1), '00:00:00')
})
