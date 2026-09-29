import test from 'node:test'
import assert from 'node:assert/strict'
import { groupOpenTasks } from './taskQueries.js'

test('task groups exclude history and partition overdue, remaining today, tomorrow and no deadline', () => {
  const now = new Date(2026, 8, 20, 12).getTime()
  const tomorrow = new Date(2026, 8, 21).getTime()
  const tasks = [
    { id: 'past', dueAt: now - 1 }, { id: 'now', dueAt: now }, { id: 'last-minute', dueAt: tomorrow - 1 },
    { id: 'tomorrow', dueAt: tomorrow }, { id: 'none', dueAt: null }, { id: 'legacy' },
    { id: 'complete', dueAt: now - 1, status: 'COMPLETED' }
  ]
  const groups = groupOpenTasks(tasks, now)
  assert.deepEqual(groups.overdue.map(t => t.id), ['past'])
  assert.deepEqual(groups.today.map(t => t.id), ['now', 'last-minute'])
  assert.deepEqual(groups.upcoming.map(t => t.id), ['tomorrow'])
  assert.deepEqual(groups.unscheduled.map(t => t.id), ['none', 'legacy'])
  assert.equal(tasks.length, 7)
})

test('equal deadlines prefer higher priority without mutating original task order', () => {
  const now = Date.now()
  const tasks = [{ id: 'low', dueAt: now, priority: 'LOW' }, { id: 'high', dueAt: now, priority: 'HIGH' }]
  assert.deepEqual(groupOpenTasks(tasks, now).today.map(t => t.id), ['high', 'low'])
  assert.equal(tasks[0].id, 'low')
})
