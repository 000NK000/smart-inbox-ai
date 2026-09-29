import test from 'node:test'
import assert from 'node:assert/strict'
import { planDays, planProblems, specialDays, practiceSummary, pickUnfamiliar } from './practicePlan.js'

test('four-week catalog contains 30 distinct problems in the agreed daily pairs', () => {
  assert.equal(planDays.length, 15)
  assert.equal(planProblems.length, 30)
  assert.equal(new Set(planProblems.map(item => item.number)).size, 30)
  assert.deepEqual(planDays[0].problems.map(item => item.number), [525, 523])
  assert.deepEqual(planDays.at(-1).problems.map(item => item.number), [90, 743])
  assert.equal(specialDays.filter(item => item.title.includes('模拟 OA')).length, 4)
})

test('personal totals include every recorded problem and legacy red counts as unfamiliar', () => {
  const result = practiceSummary([
    { number: 525, status: 'GREEN', totalMinutes: 50, nextReviewAt: null },
    { number: 523, status: 'RED', totalMinutes: 45, nextReviewAt: null },
    { number: 236, status: 'YELLOW', totalMinutes: 20, nextReviewAt: null }
  ])
  assert.equal(result.total, 3)
  assert.equal(result.independent, 1)
  assert.equal(result.unfamiliar, 2)
  assert.equal(result.suggestedAttempted, 2)
  assert.equal(result.totalMinutes, 115)
})

test('random draw uses only recorded unfamiliar problems and avoids immediate repeats', () => {
  const rows = [
    { number: 525, status: 'GREEN' },
    { number: 236, status: 'YELLOW' },
    { number: 523, status: 'RED' }
  ]
  assert.equal(pickUnfamiliar(rows, null, () => 0).number, 236)
  assert.equal(pickUnfamiliar(rows, 236, () => 0).number, 523)
  assert.equal(pickUnfamiliar([{ number: 236, status: 'YELLOW' }], 236).number, 236)
  assert.equal(pickUnfamiliar([{ number: 525, status: 'GREEN' }]), null)
})
