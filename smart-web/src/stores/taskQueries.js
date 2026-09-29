export function groupOpenTasks(tasks, now = Date.now()) {
  const tomorrow = new Date(now)
  tomorrow.setHours(24, 0, 0, 0)
  const groups = { overdue: [], today: [], upcoming: [], unscheduled: [] }
  for (const task of tasks) {
    if (task.status === 'COMPLETED') continue
    const group = task.dueAt == null ? 'unscheduled' : task.dueAt < now ? 'overdue' : task.dueAt < tomorrow.getTime() ? 'today' : 'upcoming'
    groups[group].push(task)
  }
  const weights = { HIGH: 0, NORMAL: 1, LOW: 2 }
  for (const rows of Object.values(groups)) rows.sort((a, b) =>
    (a.dueAt ?? Infinity) - (b.dueAt ?? Infinity) || (weights[a.priority || 'NORMAL'] - weights[b.priority || 'NORMAL']) || a.createdAt - b.createdAt)
  return groups
}
