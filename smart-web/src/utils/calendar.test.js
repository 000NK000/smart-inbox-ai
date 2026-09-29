import test from 'node:test'
import assert from 'node:assert/strict'
import { addDays, calendarRange, dayBounds, deadlineItems, findConflicts, itemsOnDay, localDay, overlaps, shiftPeriod } from './calendar.js'

const event = (key, startAt, endAt) => ({ key, title: key, startAt, endAt })
test('overlap is half open and empty/adjacent intervals never conflict', () => {
  assert.equal(overlaps(event('a', 10, 20), event('b', 20, 30)), false)
  assert.equal(overlaps(event('a', 10, 20), event('b', 19, 30)), true)
  assert.equal(overlaps(event('a', 10, 20), event('b', 15, 15)), false)
  const result = findConflicts([event('a', 10, 20), event('b', 15, 25), event('c', 25, 30), event('outside', 50, 60)], [], { startAt: 0, endAt: 40 })
  assert.deepEqual(result.pairs.map(pair => [pair.first.key, pair.second.key]), [['a', 'b']])
  assert.deepEqual([...result.eventKeys], ['a', 'b'])
})
test('month grids are Monday-first 42 days and month navigation clamps dates', () => {
  const range = calendarRange('2026-03-31', 'month')
  assert.equal(range.from, '2026-02-23')
  assert.equal(range.to, '2026-04-06')
  assert.equal(range.days.length, 42)
  assert.equal(new Set(range.days).size, 42)
  assert.equal(shiftPeriod('2026-01-31', 'month', 1), '2026-02-28')
  assert.equal(shiftPeriod('2024-03-31', 'month', -1), '2024-02-29')
  assert.deepEqual(calendarRange('2026-09-20', 'week').days, ['2026-09-14', '2026-09-15', '2026-09-16', '2026-09-17', '2026-09-18', '2026-09-19', '2026-09-20'])
})
test('overnight events appear on both dates and midnight endings exclude the next day', () => {
  const start = localDay('2026-09-20').getTime(), midnight = localDay('2026-09-21').getTime()
  const crossing = event('overnight', start + 23 * 3600000, midnight + 3600000)
  const ending = event('midnight', start + 22 * 3600000, midnight)
  assert.deepEqual(itemsOnDay([crossing, ending], [], '2026-09-20').map(item => item.key), ['midnight', 'overnight'])
  assert.deepEqual(itemsOnDay([crossing, ending], [], '2026-09-21').map(item => item.key), ['overnight'])
})
test('deadline markers have no duration and only open deadlines inside busy intervals get hints', () => {
  const range = { startAt: 0, endAt: 40 }
  const items = deadlineItems([
    { id: 'start', text: 'start', dueAt: 10, status: 'OPEN' },
    { id: 'end', text: 'end', dueAt: 20, status: 'OPEN' },
    { id: 'done', text: 'done', dueAt: 15, status: 'COMPLETED' },
    { id: 'later', text: 'later', dueAt: 40, status: 'OPEN' },
    { id: 'none', text: 'none', dueAt: null, status: 'OPEN' }
  ], range)
  assert.equal(items.length, 2)
  assert.equal('endAt' in items[0], false)
  const result = findConflicts([event('busy', 10, 20)], items, range)
  assert.deepEqual(result.busyDeadlines.map(pair => pair.deadline.key), ['task:start'])
  assert.equal(result.pairs.length, 0)
})
test('calendar days follow daylight saving transitions, not fixed 24-hour increments', () => {
  const previous = process.env.TZ
  process.env.TZ = 'America/New_York'
  try {
    const spring = dayBounds('2026-03-08'), fall = dayBounds('2026-11-01')
    assert.equal(spring.endAt - spring.startAt, 23 * 3600000)
    assert.equal(fall.endAt - fall.startAt, 25 * 3600000)
    assert.equal(addDays('2026-03-08', 1), '2026-03-09')
    assert.equal(calendarRange('2026-03-08', 'week').endAt, localDay('2026-03-09').getTime())
    const lateNight = event('last-hour', new Date('2026-03-09T03:30:00Z').getTime(), new Date('2026-03-09T04:00:00Z').getTime())
    assert.equal(itemsOnDay([lateNight], [], '2026-03-08').length, 1)
    assert.equal(itemsOnDay([lateNight], [], '2026-03-09').length, 0)
  } finally { if (previous == null) delete process.env.TZ; else process.env.TZ = previous }
})
