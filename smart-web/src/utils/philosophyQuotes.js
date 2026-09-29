import { shallowRef, watch } from 'vue'

// Keep the existing Chinese selection and order, including its saved index.
export const chinesePhilosophyQuotes = [
  { text: '未经省察的人生不值得过。', author: '苏格拉底' },
  { text: '知者不惑，仁者不忧，勇者不惧。', author: '孔子' },
  { text: '知人者智，自知者明。', author: '老子' },
  { text: '人生天地之间，若白驹之过隙，忽然而已。', author: '庄子' },
  { text: '困扰人的不是事情本身，而是人对事情的看法。', author: '爱比克泰德' },
  { text: '如果一个人不知道驶向哪个港口，那么任何风都不是顺风。', author: '塞涅卡' },
  { text: '我思故我在。', author: '笛卡尔' },
  { text: '人是一根会思想的芦苇。', author: '帕斯卡' },
  { text: '自由人的智慧，是对生命的沉思。', author: '斯宾诺莎' },
  { text: '敢于运用你自己的理智。', author: '康德' },
  { text: '求知是所有人的本性。', author: '亚里士多德' },
  { text: '人不能两次踏进同一条河流。', author: '赫拉克利特' },
  { text: '世界上最伟大的事，是一个人懂得如何属于自己。', author: '蒙田' },
  { text: '每个人都把自己视野的极限当作世界的极限。', author: '叔本华' },
  { text: '我的语言的界限，意味着我的世界的界限。', author: '维特根斯坦' }
]

// Original English passages, checked against editions of the authors' works.
// These are separate from the Chinese pool; no content translation is needed.
export const englishPhilosophyQuotes = [
  {
    text: 'The good life is one inspired by love and guided by knowledge.',
    author: 'Bertrand Russell',
    work: 'What I Believe, Chapter II: The Good Life (1925)',
    source: 'https://www.gutenberg.org/cache/epub/73782/pg73782-images.html'
  },
  {
    text: 'Reading maketh a full man; conference a ready man; and writing an exact man.',
    author: 'Francis Bacon',
    work: 'Essays, Of Studies (1625)',
    source: 'https://www.gutenberg.org/files/575/575-h/575-h.htm'
  },
  {
    text: 'Be a philosopher; but, amidst all your philosophy, be still a man.',
    author: 'David Hume',
    work: 'An Enquiry Concerning Human Understanding, Section I (1748)',
    source: 'https://www.gutenberg.org/files/9662/9662-h/9662-h.htm'
  },
  {
    text: 'He who knows only his own side of the case, knows little of that.',
    author: 'John Stuart Mill',
    work: 'On Liberty, Chapter II (1859)',
    source: 'https://www.gutenberg.org/files/34901/34901-h/34901-h.htm'
  },
  {
    text: 'My experience is what I agree to attend to.',
    author: 'William James',
    work: 'The Principles of Psychology, Volume I, Chapter XI: Attention (1890)',
    source: 'https://www.gutenberg.org/files/57628/57628-h/57628-h.htm'
  },
  {
    text: 'Nothing can bring you peace but yourself.',
    author: 'Ralph Waldo Emerson',
    work: 'Essays: First Series, Self-Reliance (1841)',
    source: 'https://www.gutenberg.org/cache/epub/2944/pg2944-images.html'
  }
]

export function pickPhilosophyQuote(locale, { storage, random = Math.random } = {}) {
  const isEnglish = locale === 'en-US'
  const quotes = isEnglish ? englishPhilosophyQuotes : chinesePhilosophyQuotes
  const storageKey = isEnglish ? 'smart-inbox.last-philosophy-quote.en-US' : 'smart-inbox.last-philosophy-quote'
  let previous = -1
  let localStore
  try {
    localStore = storage ?? globalThis.localStorage
    previous = Number.parseInt(localStore?.getItem(storageKey) ?? '-1', 10)
  } catch { /* Private browsing can disable storage; quotation display still works. */ }

  // Select uniformly from every entry except the one last shown in this language.
  const hasPrevious = Number.isInteger(previous) && previous >= 0 && previous < quotes.length
  let index = Math.floor(random() * (quotes.length - (hasPrevious ? 1 : 0)))
  if (hasPrevious && index >= previous) index++
  try { localStore?.setItem(storageKey, String(index)) } catch { /* Storage is optional. */ }
  return quotes[index]
}

export function usePhilosophyQuote(locale, options) {
  const quote = shallowRef()
  watch(locale, value => { quote.value = pickPhilosophyQuote(value, options) }, { immediate: true })
  return quote
}
