const mediaSites = {
  CNN: { domains: ['cnn.com'], home: 'https://www.cnn.com/' },
  'NBC News': { domains: ['nbcnews.com'], home: 'https://www.nbcnews.com/' },
  'ABC News': { domains: ['abcnews.go.com', 'abcnews.com'], home: 'https://abcnews.go.com/' },
  'CBS News': { domains: ['cbsnews.com'], home: 'https://www.cbsnews.com/' },
  NPR: { domains: ['npr.org'], home: 'https://www.npr.org/' }
}

export function newsOfficialLink(item) {
  const site = mediaSites[item?.source]
  if (!site) return { url: '', article: false }
  try {
    const url = new URL(item.url)
    if (url.protocol === 'https:' && !url.username && !url.password && (!url.port || url.port === '443') &&
        site.domains.some(domain => url.hostname === domain || url.hostname.endsWith(`.${domain}`))) {
      return { url: url.href, article: true }
    }
  } catch { /* Missing/indirect article links use the publisher homepage. */ }
  return { url: site.home, article: false }
}
