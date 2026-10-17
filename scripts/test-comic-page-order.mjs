// Tests for a comic's PAGE ORDER — the rule comic-book.js and CbzDirectory.kt must share.
//   node scripts/test-comic-page-order.mjs
// Michael 2026-10-11, comics slice 2: the balloon index names a page by its place in this order,
// so the JavaScript spine and the Kotlin indexer change together. BalloonIndexTest
// (spineRuleIsComicBookJs, naturalPageOrderSharedVector, naturalCompareVectors) pins these SAME
// vectors on the Kotlin side, and the iPad's BalloonGeometryTests (testSpineRuleIsComicBookJs,
// testNaturalPageOrderSharedVector) pins them in Swift — change one, change all three.
import { comicPageNames, naturalCompare, isPageName, legacyPageNames, comicPositions, makeComicBook }
    from '../app/src/main/assets/foliate/comic-book.js'

let failures = 0
const check = (name, cond, extra = '') => {
    if (cond) console.log(`  ok  ${name}`)
    else { failures++; console.error(`FAIL  ${name}  ${extra}`) }
}
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b)

// ---- the spine: which names are pages, in which order ---------------------------------------
// THE SHARED VECTOR — byte-identical to the iPad's testNaturalPageOrderSharedVector.
const shared = ['page10.jpg', 'page2.jpg', 'page1.jpg', 'Page3.JPG', 'cover.Jpeg', 'ComicInfo.xml',
    'folder/', 'notes.txt', '01.png', '1.png', 'ch10/p1.jpg', 'ch2/p10.jpg', 'ch2/p9.jpg',
    'é.jpg', 'z.webp', 'img007.png', 'img7.png']
const sharedWant = ['01.png', '1.png', 'Page3.JPG', 'ch2/p9.jpg', 'ch2/p10.jpg', 'ch10/p1.jpg',
    'cover.Jpeg', 'img007.png', 'img7.png', 'page1.jpg', 'page2.jpg', 'page10.jpg', 'z.webp', 'é.jpg']
check('the shared page-order vector (iPad + Kotlin)', same(comicPageNames(shared), sharedWant),
    JSON.stringify(comicPageNames(shared)))
// …and the iPad's testSpineRuleIsComicBookJs: `.JPG` is a page; case orders by code unit.
check('the shared spine rule (iPad + Kotlin)', same(comicPageNames(
    ['b.jpg', 'a.JPG', 'Dir/c.png', 'a.jpg', 'ComicInfo.xml', 'x.jpeg', 'folder/', 'FileInfo.txt']),
    ['Dir/c.png', 'a.JPG', 'a.jpg', 'b.jpg', 'x.jpeg']))

const names = ['page10.jpg', 'page2.jpg', 'Page1.JPG', 'page01.jpg', 'b.jpg', 'a.JPG', 'Dir/c.png',
    'a.jpg', 'ComicInfo.xml', 'x.jpeg', 'folder/', 'FileInfo.txt', 'img.Webp', 'ch 1/p 20.avif',
    'ch 1/p 3.avif', 'notes.jpg.txt', 'v2/007.png', 'v10/001.png', '0.gif', '00.gif']
const want = ['0.gif', '00.gif', 'Dir/c.png', 'Page1.JPG', 'a.JPG', 'a.jpg', 'b.jpg', 'ch 1/p 3.avif',
    'ch 1/p 20.avif', 'img.Webp', 'page01.jpg', 'page2.jpg', 'page10.jpg', 'v2/007.png',
    'v10/001.png', 'x.jpeg']
const got = comicPageNames(names)
check('the wider spine vector', same(got, want), JSON.stringify(got))
check('input order never changes the result', same(comicPageNames([...names].reverse()), want))

// ---- the comparison itself -----------------------------------------------------------------
const sign = n => (n > 0) - (n < 0)
check('page2 < page10', sign(naturalCompare('page2.jpg', 'page10.jpg')) === -1)
check('leading zeros are the same number; tie → code units', sign(naturalCompare('p01', 'p1')) === -1)
check('case is NOT folded: Z before a (code units)', sign(naturalCompare('Z.jpg', 'a.jpg')) === -1)
check('case is NOT folded: credits after Page01', sign(naturalCompare('credits.jpg', 'Page01.jpg')) === 1)
check('a name that runs out first sorts first', sign(naturalCompare('p1', 'p1a')) === -1)
check('a digit run too long for a number still compares',
    sign(naturalCompare('x123456789012345678901234567890', 'x123456789012345678901234567891')) === -1)
check('identical names are equal', naturalCompare('a1b', 'a1b') === 0)
check('x9 < x10, x < x0 (the iPad\'s checks)',
    sign(naturalCompare('x9', 'x10')) === -1 && sign(naturalCompare('x', 'x0')) === -1)
const padded = Array.from({ length: 12 }, (_, i) => String(i + 1).padStart(3, '0') + '.jpg')
check('zero-padded names keep the old .sort() order', same([...padded].sort(naturalCompare), [...padded].sort()))

// ---- extensions ----------------------------------------------------------------------------
check('.JPG is a page', isPageName('001.JPG'))
check('.Jpeg is a page', isPageName('x/001.Jpeg'))
check('a folder is not a page', !isPageName('folder.jpg/'))
check('ComicInfo.xml is not a page', !isPageName('ComicInfo.xml'))

// ---- saved positions across the order change -------------------------------------------------
// Old spine: ['page1.jpg', 'page10.jpg', 'page2.jpg'] (code units; '.JPG' invisible).
// New spine: ['Cover.JPG', 'page1.jpg', 'page2.jpg', 'page10.jpg'].
const book = ['page10.jpg', 'page2.jpg', 'page1.jpg', 'Cover.JPG']
const files = comicPageNames(book)
check('legacy spine is the old filter + sort', same(legacyPageNames(book), ['page1.jpg', 'page10.jpg', 'page2.jpg']))
const pos = comicPositions(book, files)
check('a book the order moved gets a position map', pos != null)
// An OLD position — no name, old index — is read in the old spine and mapped by file name.
check('old /6/4 (old page10) → new page10', pos.resolveCFI('epubcfi(/6/4)').index === 3)
check('old /6/6 (old page2) → new page2', pos.resolveCFI('epubcfi(/6/6)').index === 2)
check('old /6/2 (old page1) → new page1', pos.resolveCFI('epubcfi(/6/2)').index === 1)
// A NEW position names its page, keeps the OLD index in its step (older readers land right).
check('new CFI for page10 = old step + name', pos.cfiOf(3) === 'epubcfi(/6/4[page10.jpg])', pos.cfiOf(3))
check('a page the old order never showed uses its new index', pos.cfiOf(0) === 'epubcfi(/6/2[Cover.JPG])')
for (let i = 0; i < files.length; i++)
    check(`new CFI round-trips (page ${i})`, pos.resolveCFI(pos.cfiOf(i)).index === i)
// The bookmark alias: the unnamed CFI the old order gave a page (an old bookmark's exact string).
check('legacy CFI of page10 is the old /6/4', pos.legacyCfiOf(3) === 'epubcfi(/6/4)', pos.legacyCfiOf(3))
check('a page the old order never showed has no legacy CFI', pos.legacyCfiOf(0) === null)
for (let i = 1; i < files.length; i++)
    check(`legacy CFI resolves to its own page (page ${i})`, pos.resolveCFI(pos.legacyCfiOf(i)).index === i)
// A highlight inside a page keeps its in-page path; ids with CFI metacharacters escape cleanly.
const odd = ['a]b,(1).jpg', 'a]b,(10).jpg', 'a]b,(2).jpg']
const oddPos = comicPositions(odd, comicPageNames(odd))
check('escaped names round-trip', [0, 1, 2].every(i => oddPos.resolveCFI(oddPos.cfiOf(i)).index === i))
const hl = pos.resolveCFI('epubcfi(/6/4[page10.jpg]!/4/2)')
check('an in-page range keeps its page and its path', hl.index === 3 && typeof hl.anchor === 'function')
// A zero-padded book never moved: no map, and its CFIs stay exactly as before.
check('a zero-padded book is untouched', comicPositions(padded, comicPageNames(padded)) === null)

// makeComicBook wires it: sections carry the named CFIs only when the order moved.
const loader = list => ({ entries: list.map(filename => ({ filename })), loadBlob: async () => new Blob([]),
    getSize: () => 1, getComment: async () => '' })
const moved = await makeComicBook(loader(book), { name: 'x.cbz' })
check('a moved book resolves old positions by name', moved.resolveCFI('epubcfi(/6/4)').index === 3 &&
    moved.sections[3].cfi === 'epubcfi(/6/4[page10.jpg])' && moved.sections[3].legacyCfi === 'epubcfi(/6/4)' &&
    moved.sections[0].legacyCfi === null)
const kept = await makeComicBook(loader(padded), { name: 'y.cbz' })
check('an unmoved book keeps foliate\'s own CFIs', kept.resolveCFI === undefined && kept.sections[0].cfi === undefined)

if (failures) { console.error(`${failures} failure(s)`); process.exit(1) }
console.log('all page-order checks passed')
