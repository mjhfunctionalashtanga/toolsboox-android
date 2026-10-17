import * as CFI from './epubcfi.js'

// Read series metadata from a ComicInfo.xml entry, if present.
// Spec: https://anansi-project.github.io/docs/comicinfo/intro
const readComicInfoXML = async ({ entries, loadBlob }) => {
    const entry = entries.find(e => e.filename.toLowerCase() === 'comicinfo.xml')
        ?? entries.find(e => e.filename.split('/').pop()?.toLowerCase() === 'comicinfo.xml')
    if (!entry) return null
    let text
    try {
        text = await (await loadBlob(entry.filename)).text()
    } catch {
        return null
    }
    let doc
    try {
        doc = new DOMParser().parseFromString(text, 'application/xml')
    } catch {
        return null
    }
    if (!doc || doc.getElementsByTagName('parsererror').length) return null
    const get = name => doc.getElementsByTagName(name).item(0)?.textContent?.trim() || undefined
    const getPositiveInteger = name => {
        const value = Number.parseInt(get(name), 10)
        return Number.isFinite(value) && value > 0 ? value : undefined
    }
    const getSubjects = () => [...new Set([get('Genre'), get('Tags')]
        .flatMap(value => value?.split(/[,;|]/).map(x => x.trim()).filter(Boolean) ?? []))]
    const getPublished = () => {
        const year = getPositiveInteger('Year')
        if (!year) return undefined
        const month = getPositiveInteger('Month')
        const day = getPositiveInteger('Day')
        const yyyy = String(year).padStart(4, '0')
        if (!month || month > 12) return yyyy
        const yyyyMm = `${yyyy}-${String(month).padStart(2, '0')}`
        if (!day || day > 31) return yyyyMm
        return `${yyyyMm}-${String(day).padStart(2, '0')}`
    }
    const subjects = getSubjects()
    return {
        title: get('Title'),
        publisher: get('Publisher'),
        language: get('LanguageISO'),
        author: get('Writer'),
        published: getPublished(),
        description: get('Summary'),
        subject: subjects.length ? subjects : undefined,
        identifier: get('Web'),
        series: get('Series'),
        seriesPosition: get('Number'),
        seriesTotal: get('Count'),
    }
}

const readComicBookInfo = async ({ getComment }) => {
    let info
    try {
        info = JSON.parse(await getComment() || '')['ComicBookInfo/1.0']
    } catch {
        return null
    }
    if (!info) return null
    const year = info.publicationYear
    const month = info.publicationMonth
    const mm = month && month >= 1 && month <= 12 ? String(month).padStart(2, '0') : null
    return {
        title: info.title,
        publisher: info.publisher,
        language: info.language || info.lang,
        author: info.credits ? info.credits.map(c => `${c.person} (${c.role})`).join(', ') : '',
        published: year && month ? `${year}-${mm}` : undefined,
        series: info.series,
        seriesPosition: info.issue == null ? undefined : String(info.issue),
    }
}

// THE PAGE ORDER — twin of CbzDirectory.kt (isPageName / naturalCompare / pages) and of the
// iPad's comic-book.js + BalloonGeometry.naturalLess; Ledgable, Michael 2026-10-11, comics
// slice 2. The balloon index names pages by their position here, so every side changes together
// and scripts/test-comic-page-order.mjs, BalloonIndexTest and the iPad's BalloonGeometryTests pin
// the same vectors. Was: case-sensitive extensions (a `.JPG` page vanished) and plain `.sort()`
// (`page10` before `page2`). Now Mihon's rule, written out by hand — never a locale sort:
//  - extensions match ASCII-case-insensitively;
//  - runs of ASCII digits compare as numbers (leading zeros ignored; by length, then digits);
//  - every other UTF-16 code unit compares AS ITSELF — case is NOT folded (the iPad's rule: only
//    digit runs differ from the old `.sort()`, so fewer saved positions move, and both apps give
//    one CBZ the same page numbers);
//  - a name that runs out first sorts first; still equal → plain code-unit order.
const PAGE_EXTS = ['.jpg', '.jpeg', '.png', '.gif', '.bmp', '.webp', '.svg', '.jxl', '.avif']
const asciiLower = s => s.replace(/[A-Z]/g, c => String.fromCharCode(c.charCodeAt(0) + 32))
export const isPageName = name => {
    if (name.endsWith('/')) return false
    const tail = asciiLower(name.slice(-6))
    return PAGE_EXTS.some(ext => tail.endsWith(ext))
}
const isDigit = c => c >= 48 && c <= 57
export const naturalCompare = (a, b) => {
    let i = 0, j = 0
    while (i < a.length && j < b.length) {
        const ca = a.charCodeAt(i), cb = b.charCodeAt(j)
        if (isDigit(ca) && isDigit(cb)) {
            let si = i; while (si < a.length && a.charCodeAt(si) === 48) si++
            let sj = j; while (sj < b.length && b.charCodeAt(sj) === 48) sj++
            let ei = si; while (ei < a.length && isDigit(a.charCodeAt(ei))) ei++
            let ej = sj; while (ej < b.length && isDigit(b.charCodeAt(ej))) ej++
            if (ei - si !== ej - sj) return ei - si < ej - sj ? -1 : 1
            for (let k = 0; k < ei - si; k++) {
                const da = a.charCodeAt(si + k), db = b.charCodeAt(sj + k)
                if (da !== db) return da < db ? -1 : 1
            }
            i = ei; j = ej
            continue
        }
        if (ca !== cb) return ca < cb ? -1 : 1
        i++; j++
    }
    const ra = a.length - i, rb = b.length - j
    if (ra !== rb) return ra < rb ? -1 : 1
    return a < b ? -1 : a > b ? 1 : 0
}
export const comicPageNames = names => names.filter(isPageName).sort(naturalCompare)

// THE SPINE BEFORE SLICE 2: exact-case extensions, plain code-unit `.sort()`. Kept only to read
// the positions written under it (see comicPositions).
export const legacyPageNames = names => names.filter(name => PAGE_EXTS.some(ext => name.endsWith(ext))).sort()

/**
 * SAVED POSITIONS SURVIVE THE NEW ORDER (Michael 2026-10-11, comics slice 2 review). A comic's
 * reading position, bookmarks, highlights and synced spot are index-based CFIs (`/6/<2(i+1)>`,
 * view.js getCFI), so a book whose pages the natural order renumbers — unpadded `page2`/`page10`,
 * newly visible `.JPG` pages — would reopen on the wrong page. Only for THOSE books (the order
 * moved; Mihon's zero-padded downloads never do, and their CFIs stay byte-for-byte as before):
 *  - every position written from now on NAMES its page: `/6/<step>[<page name>]`, resolved by
 *    name, so it is mapped exactly once and never again by index;
 *  - its step stays the page's OLD index, so a reader still on the old order (the Boox fleet's
 *    build, an older iPad) lands on the same page from a position this one synced (a page the
 *    old order never showed uses its new index);
 *  - a position with no name was written under the old order (this device before the update,
 *    or an older one via the hub): its index is read in the OLD spine and mapped by file name;
 *  - `legacyCfiOf(i)` is the unnamed CFI the old order gave page i (null for a page it never
 *    showed) — the app matches a bookmark saved before the update against it, so that page
 *    still reads as bookmarked instead of collecting a duplicate.
 * Null when the order did not move. `files` is comicPageNames(names).
 */
export const comicPositions = (names, files) => {
    const legacy = legacyPageNames(names)
    if (legacy.length === files.length && legacy.every((n, i) => n === files[i])) return null
    const now = new Map(files.map((n, i) => [n, i]))
    const before = new Map(legacy.map((n, i) => [n, i]))
    const esc = s => s.replace(/[\^[\](),;=]/g, '^$&')   // epubcfi.js's escape for an id
    return {
        cfiOf: i => `epubcfi(/6/${((before.get(files[i]) ?? i) + 1) * 2}[${esc(files[i])}])`,
        legacyCfiOf: i => before.has(files[i]) ? `epubcfi(/6/${(before.get(files[i]) + 1) * 2})` : null,
        resolveCFI: cfi => {
            const parts = CFI.parse(cfi)
            const head = (parts.parent ?? parts).shift()
            const step = head?.at(-1)
            let index = CFI.fake.toIndex(head)
            if (step?.id != null && now.has(step.id)) index = now.get(step.id)
            else if (step?.id == null && legacy[index] != null) index = now.get(legacy[index]) ?? index
            return { index, anchor: doc => CFI.toRange(doc, parts) }
        },
    }
}

export const makeComicBook = async ({ entries, loadBlob, getSize, getComment }, file) => {
    const cache = new Map()
    const urls = new Map()
    const load = async name => {
        if (cache.has(name)) return cache.get(name)
        const src = URL.createObjectURL(await loadBlob(name))
        const page = URL.createObjectURL(
            new Blob([`<!DOCTYPE html><html><head><meta charset="utf-8"></head><body style="margin: 0"><img src="${src}"></body></html>`], { type: 'text/html' }))
        urls.set(name, [src, page])
        cache.set(name, page)
        return page
    }
    const unload = name => {
        urls.get(name)?.forEach?.(url => URL.revokeObjectURL(url))
        urls.delete(name)
        cache.delete(name)
    }

    const names = entries.map(entry => entry.filename)
    const files = comicPageNames(names)
    if (!files.length) throw new Error('No supported image files in archive')

    const book = {}
    // Prefer ComicInfo.xml (Anansi standard) over ComicBookInfo (JSON in zip comment).
    // Fields missing from the preferred source fall through to the secondary one.
    const xml = await readComicInfoXML({ entries, loadBlob })
    const cbi = await readComicBookInfo({ getComment })
    const merged = { ...(cbi || {}), ...(xml || {}) }
    book.metadata = {
        title: merged.title || file.name,
        publisher: merged.publisher,
        language: merged.language,
        author: merged.author,
        published: merged.published,
        description: merged.description,
        subject: merged.subject,
        identifier: merged.identifier,
    }
    if (merged.series) {
        const series = { name: merged.series }
        if (merged.seriesPosition) series.position = merged.seriesPosition
        if (merged.seriesTotal) series.total = merged.seriesTotal
        book.metadata.belongsTo = { series }
    }
    book.getCover = () => loadBlob(files[0])
    const moved = comicPositions(names, files)
    book.sections = files.map((name, i) => ({
        id: name,
        load: () => load(name),
        unload: () => unload(name),
        size: getSize(name),
        ...(moved ? { cfi: moved.cfiOf(i), legacyCfi: moved.legacyCfiOf(i) } : {}),
    }))
    if (moved) book.resolveCFI = moved.resolveCFI
    book.toc = files.map(name => ({ label: name, href: name }))
    book.rendition = { layout: 'pre-paginated' }
    book.resolveHref = href => ({ index: book.sections.findIndex(s => s.id === href) })
    book.splitTOCHref = href => [href, null]
    book.getTOCFragment = doc => doc.documentElement
    book.destroy = () => {
        for (const arr of urls.values())
            for (const url of arr) URL.revokeObjectURL(url)
    }
    return book
}
