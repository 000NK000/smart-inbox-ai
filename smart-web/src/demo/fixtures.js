// Fictional fixtures only. No values are imported from a personal workspace.
export function createFixtures(now = Date.now()) {
  const ago = hours => now - hours * 3600000
  const mails = [
    { id: 901, source: 'OUTLOOK', subject: 'Northstar Labs: technical interview invitation', sender: 'Talent Team <careers@northstar.example.com>', summary: 'Choose an interview slot and review the backend engineering interview guide.', urgency: 6, category: 'Work', createdTime: ago(1), inboxRead: false, starred: true,
      content: 'Hello Alex,\n\nThank you for applying to our Software Engineer Intern role. We would like to invite you to a 45-minute technical interview. Please reply with your preferred time slot by Friday.\n\nPrepare to discuss a project, Java fundamentals, and a coding problem.\n\nBest,\nThe Northstar Labs team' },
    { id: 902, source: 'GMAIL', subject: 'Distributed Systems: project milestone due Friday', sender: 'Course Team <course@example.com>', summary: 'Submit the design document and describe the retry strategy for your message consumer.', urgency: 5, category: 'Work', createdTime: ago(3), inboxRead: false, starred: false, content: 'Please submit your design document by Friday. Include the system architecture, idempotency strategy, and failure handling tests.' },
    { id: 903, source: 'QQMAIL', subject: 'French conversation group: this week’s session', sender: 'Language Club <club@example.com>', summary: 'Optional conversation practice on Thursday. Bring one topic you would like to discuss.', urgency: 2, category: 'Personal', createdTime: ago(5), inboxRead: false, starred: false, content: 'Our optional conversation session is on Thursday. You are welcome to bring one discussion topic.' },
    { id: 904, source: 'GMAIL', subject: 'Your weekly engineering reading list', sender: 'Engineering Digest <digest@example.com>', summary: 'This week: caching trade-offs, database indexes, and reliable background processing.', urgency: 1, category: 'News', createdTime: ago(8), inboxRead: false, starred: false, content: 'Explore three topics this week: caching trade-offs, database indexes, and background processing. No action required.' },
  ]
  const applications = [
    { id: 'demo-1', company: 'Northstar Labs', role: 'Software Engineer Intern', stage: 'APPLIED', location: 'Remote', notes: 'Backend platform team · Java and distributed systems', linkedMails: [mails[0]], version: 0 },
    { id: 'demo-2', company: 'Maple Cloud', role: 'Backend Engineering Intern', stage: 'ASSESSMENT', location: 'Toronto', notes: 'Practice data structures and explain time complexity.', linkedMails: [], version: 0 },
    { id: 'demo-3', company: 'Harbor Systems', role: 'Platform Engineer Intern', stage: 'INTERVIEW', location: 'Remote', notes: 'Review message retries, consistency, and API design.', linkedMails: [], version: 0 },
    { id: 'demo-4', company: 'Aurora Works', role: 'Full-stack Developer Intern', stage: 'PREPARING', location: 'Hybrid', notes: 'Tailor project examples to the role.', linkedMails: [], version: 0 },
  ].map(app => ({ ...app, appliedAt: ago(48), nextActionAt: now + 86400000, linkedMails: app.linkedMails.map(mail => ({ ...mail, receivedAt: mail.createdTime })) }))
  const day = new Date(now).toISOString().slice(0, 10)
  return { now, mails, applications, weather: { timezone: 'UTC',
    current: { time: day + 'T12:00', temperature_2m: 21, apparent_temperature: 20, relative_humidity_2m: 55, wind_speed_10m: 8, weather_code: 2 },
    hourly: { time: Array.from({ length: 48 }, (_, i) => new Date(new Date(day).getTime() + i * 3600000).toISOString().slice(0, 16)), temperature_2m: Array.from({ length: 48 }, (_, i) => 18 + Math.round(4 * Math.sin(i / 5))), precipitation_probability: Array(48).fill(5), weather_code: Array(48).fill(2), wind_speed_10m: Array(48).fill(8) } },
    tasks: [{ id: 'demo-task-1', text: 'Prepare interview project walkthrough', priority: 'HIGH', status: 'OPEN', dueAt: now + 86400000, notes: 'Explain idempotency, bounded AI concurrency, and one debugging story.', version: 0 }, { id: 'demo-task-2', text: 'Review the distributed systems design document', priority: 'NORMAL', status: 'OPEN', dueAt: now + 172800000, notes: '', version: 0 }],
    suggestions: [{ mailId: 901, mailSubject: mails[0].subject, sender: mails[0].sender, receivedAt: mails[0].createdTime, company: 'Northstar Labs', role: 'Software Engineer Intern', matchedApplicationId: 'demo-1', suggestedStage: 'INTERVIEW', summary: 'A technical interview invitation is ready to review.', evidence: 'We would like to invite you to a 45-minute technical interview.', preparations: [{ title: 'Prepare a project walkthrough', details: 'Explain the architecture, one trade-off, and how you tested failure cases.' }], version: 0 }],
  }
}

export function createDemoAdapter(fixtures = createFixtures()) {
  const status = { status: 'COMPLETED', total: 4, processed: 4, failed: 0 }
  return async config => {
    const url = new URL(config.url, 'http://demo.invalid')
    const path = url.pathname
    const method = (config.method || 'get').toLowerCase()
    let data
    const params = config.params || {}
    const visible = fixtures.mails.filter(m => !m.inboxRead && (params.source == null || m.source === params.source) && (params.view !== 'STARRED' || m.starred) && (!params.q || (m.subject + m.content).toLowerCase().includes(params.q.toLowerCase())))
    if (path === '/api/mails/revision') data = { totalElements: visible.length, version: 'demo-1' }
    else if (path === '/api/mails/summaries') data = { content: visible, totalElements: visible.length }
    else if (/^\/api\/mails\/\d+$/.test(path)) data = fixtures.mails.find(m => String(m.id) === path.split('/').at(-1))
    else if (path === '/api/tasks/summary') data = { version: 'demo-1', total: 2, open: 2, completed: 0, dueToday: 1, overdue: 0, upcoming: 1, noDeadline: 0 }
    else if (path === '/api/tasks' && method === 'get') data = fixtures.tasks
    else if (path === '/api/dashboard/preferences') data = { remindersEnabled: 'false', weatherLocation: 'waterloo' }
    else if (path === '/api/dashboard/weather') data = fixtures.weather
    else if (['/api/outlook/status', '/api/outlook/refresh'].includes(path)) data = { connected: true, configured: true, sync: { syncing: false, lastSuccess: fixtures.now } }
    else if (path === '/api/job-applications/analysis') data = status
    else if (path === '/api/job-applications' && method === 'get') data = { applications: fixtures.applications, suggestions: fixtures.suggestions, stageCounts: { PREPARING: 1, APPLIED: 1, ASSESSMENT: 1, INTERVIEW: 1, RESULT: 0 }, analysis: status }
    else if (path === '/api/mails/reminders') data = []
    else throw Object.assign(new Error('This action is unavailable in the read-only demo.'), { response: { status: 403, data: { message: 'This action is unavailable in the read-only demo.' } } })
    return { status: 200, statusText: 'OK', data: structuredClone(data), headers: {}, config }
  }
}

// The public preview has no phone identity or live runtime. Supply only the
// two boot-time reads; every other Fetch call remains isolated from the network.
export function createDemoFetch() {
  return async (input, options = {}) => {
    const url = typeof input === 'string' ? input : input.url
    const method = (options.method || input?.method || 'GET').toUpperCase()
    const contexts = {
      '/api/mobile/context': { mobile: false },
      '/api/runtime/status': { mode: 'active', message: 'Read-only demo' },
    }
    if (method !== 'GET' || !Object.hasOwn(contexts, url)) {
      throw new Error('Network requests are disabled in the sample workspace.')
    }
    return new Response(JSON.stringify(contexts[url]), { headers: { 'Content-Type': 'application/json' } })
  }
}
