// Dates here are display dates in the browser's time zone. Recurrence expansion
// belongs to the server, which retains each event's original IANA time zone.
const pad = value => String(value).padStart(2, '0')
export function dayKey(value = new Date()) {
  const date = value instanceof Date ? value : new Date(value)
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}
export function localDay(key) {
  const [year, month, day] = key.split('-').map(Number)
  return new Date(year, month - 1, day)
}
export function addDays(key, amount) {
  const date = localDay(key)
  date.setDate(date.getDate() + amount)
  return dayKey(date)
}
export function shiftPeriod(key, view, amount) {
  if (view === 'week') return addDays(key, amount * 7)
  const date = localDay(key), wantedDay = date.getDate()
  date.setDate(1)
  date.setMonth(date.getMonth() + amount)
  const lastDay = new Date(date.getFullYear(), date.getMonth() + 1, 0).getDate()
  date.setDate(Math.min(wantedDay, lastDay))
  return dayKey(date)
}
export function calendarRange(anchor, view) {
  const date = localDay(anchor)
  if (view === 'month') date.setDate(1)
  const from = addDays(dayKey(date), -((date.getDay() + 6) % 7))
  const days = Array.from({ length: view === 'month' ? 42 : 7 }, (_, index) => addDays(from, index))
  const to = addDays(from, days.length)
  return { from, to, days, startAt: localDay(from).getTime(), endAt: localDay(to).getTime() }
}
export function overlaps(left, right) {
  return left.startAt < left.endAt && right.startAt < right.endAt && left.startAt < right.endAt && right.startAt < left.endAt
}
export function dayBounds(key) {
  return { startAt: localDay(key).getTime(), endAt: localDay(addDays(key, 1)).getTime() }
}
export function deadlineItems(tasks, range) {
  return tasks.filter(task => task.status !== 'COMPLETED' && task.dueAt != null && Number.isFinite(Number(task.dueAt)) &&
    Number(task.dueAt) >= range.startAt && Number(task.dueAt) < range.endAt)
    .map(task => ({ key: 'task:' + task.id, title: task.text, kind: 'TASK', startAt: Number(task.dueAt), task }))
}
export function itemsOnDay(events, deadlines, key) {
  const bounds = dayBounds(key)
  return [...events.filter(event => overlaps(event, bounds)), ...deadlines.filter(item => item.startAt >= bounds.startAt && item.startAt < bounds.endAt)]
    .sort((left, right) => left.startAt - right.startAt || left.title.localeCompare(right.title))
}
export function findConflicts(events, deadlines, range) {
  const visible = events.filter(event => overlaps(event, range)).slice().sort((left, right) => left.startAt - right.startAt)
  const pairs = [], busyDeadlines = [], eventKeys = new Set(), taskKeys = new Set()
  for (let i = 0; i < visible.length; i++) {
    for (let j = i + 1; j < visible.length && visible[j].startAt < visible[i].endAt; j++) {
      if (overlaps(visible[i], visible[j])) {
        pairs.push({ first: visible[i], second: visible[j], startAt: Math.max(visible[i].startAt, visible[j].startAt, range.startAt) })
        eventKeys.add(visible[i].key); eventKeys.add(visible[j].key)
      }
    }
  }
  for (const deadline of deadlines) {
    if (deadline.task?.status === 'COMPLETED' || deadline.startAt < range.startAt || deadline.startAt >= range.endAt) continue
    const during = visible.filter(event => deadline.startAt >= event.startAt && deadline.startAt < event.endAt)
    if (during.length) { busyDeadlines.push({ deadline, events: during }); taskKeys.add(deadline.key) }
  }
  return { pairs, busyDeadlines, eventKeys, taskKeys }
}
