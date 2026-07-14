// Headless metadata + cover extractor. Reuses foliate's makeBook to read the
// book served at reader://app/current-book, then posts title/author/cover back
// to Swift without rendering anything. Covers every format makeBook supports.

const post = (type, payload = {}) =>
    window.webkit?.messageHandlers?.extract?.postMessage({ type, ...payload })

const langMap = x => !x ? '' : (typeof x === 'string' ? x : x[Object.keys(x)[0]])
const contributor = c => Array.isArray(c)
    ? c.map(x => typeof x === 'string' ? x : langMap(x?.name)).filter(Boolean).join(', ')
    : (typeof c === 'string' ? c : langMap(c?.name))

const blobToDataURL = blob => new Promise((resolve, reject) => {
    const r = new FileReader()
    r.onload = () => resolve(r.result)
    r.onerror = reject
    r.readAsDataURL(blob)
})

try {
    const { makeBook } = await import('./view.js')
    const book = await makeBook('reader://app/current-book')
    const md = book.metadata || {}
    let cover = ''
    try {
        const blob = await book.getCover?.()
        if (blob) cover = await blobToDataURL(blob)
    } catch (e) { /* no cover — fine */ }
    post('meta', {
        title: langMap(md.title) || '',
        author: contributor(md.author) || '',
        cover,
    })
} catch (e) {
    post('error', { message: String(e?.message || e) })
}
