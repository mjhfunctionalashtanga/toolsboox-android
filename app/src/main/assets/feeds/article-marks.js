/*
 * article-marks.js — an article's highlights, drawn back on every device (Michael 2026-10-12:
 * the approved highlights+notes plan; "gram picks should highlight back too").
 *
 * IDENTICAL IN BOTH APPS (Android assets/feeds/, the iOS bundle's Feed/). One object,
 * window.__ledgerMarks:
 *   capture()      → JSON {exact,prefix,suffix,fraction,overlapId,links} for the live selection
 *                    (or the one remembered within 20 s), or null. The passage and its context come
 *                    from the SAME cleaned page text paint() searches — never Selection.toString(),
 *                    which carries the link lines the selection bridge appends (design critique #6).
 *   paint(list)    → draws [{id,exact,prefix,suffix,noted}] and returns a JSON array of the ids it
 *                    could not place. A passage matches IN FULL (never shortened); more than one
 *                    occurrence is chosen by the text either side; none = "not on this version".
 *                    One highlight per passage: a mark that would land on one already drawn is not
 *                    drawn twice.
 *   jump(id)       → scrolls the passage a third of the way down, instantly (no animation: e-ink).
 *   hitAt(x, y)    → the id drawn under a client point, or "" — for the page-turn corners.
 * A tap on a drawn passage (not a link, not while text is selected) navigates to
 * ledger-mark://<id>; the app catches that. Hit-testing reads the EXACT ranges paint() drew.
 *
 * Looks: the book's mark (reader-embed.js einkHighlight) — a 0.3 yellow band and a 3px black
 * underline (white on a dark page); a mark with a note on it wears a DOUBLE underline instead of
 * an overlay that would need repositioning on every reflow (critique #8). CSS.highlights where the
 * WebView has it (no DOM change at all), else one span per touched text node.
 */
(function () {
  if (window.__ledgerMarks) return;
  var FRESH_MS = 20000;
  var BLOCK = /^(ADDRESS|ARTICLE|ASIDE|BLOCKQUOTE|BODY|CAPTION|DD|DETAILS|DIV|DL|DT|FIELDSET|FIGCAPTION|FIGURE|FOOTER|FORM|H1|H2|H3|H4|H5|H6|HEADER|HR|LI|MAIN|NAV|OL|P|PRE|SECTION|SUMMARY|TABLE|TBODY|TD|TFOOT|TH|THEAD|TR|UL)$/;
  var SKIP = 'script,style,noscript,template,svg,select,textarea,[data-ledger-ui]';
  var painted = [];   // [{id, range, noted, s, e}]
  var spans = [];     // fallback spans, unwrapped before every repaint

  function normChar(c) {
    if (c === '\u2018' || c === '\u2019' || c === '\u201A' || c === '\u2032') return "'";
    if (c === '\u201C' || c === '\u201D' || c === '\u201E' || c === '\u2033') return '"';
    if (c === '\u00AD' || c === '\u200B' || c === '\u200C' || c === '\u200D' || c === '\uFEFF') return '';
    if (/\s/.test(c) || c === '\u00A0' || c === '\u2007' || c === '\u202F') return ' ';
    return c;
  }

  function normText(raw) {
    var out = '', s = String(raw || '');
    for (var i = 0; i < s.length; i++) {
      var c = normChar(s[i]);
      if (!c) continue;
      if (c === ' ' && (out === '' || out[out.length - 1] === ' ')) continue;
      out += c;
    }
    return out.replace(/ $/, '');
  }

  function blockOf(n, body) {
    var e = n.parentElement;
    while (e && e !== body) { if (BLOCK.test(e.tagName)) return e; e = e.parentElement; }
    return body;
  }

  // The page's text, normalised, with a map back to (node, raw offset). A space stands at every
  // block boundary and <br> that the text itself didn't already space (critique #6) — so a
  // passage across two paragraphs matches whether or not the markup had whitespace between them.
  // Virtual spaces map to [next node, -0.5], which keeps the map sorted.
  function build(d) {
    var body = d.body, nodes = [], norm = '', map = [], lastBlock = null;
    if (!body) return { nodes: nodes, norm: norm, map: map, index: new Map() };
    var w = d.createTreeWalker(body, NodeFilter.SHOW_TEXT, { acceptNode: function (n) {
      var p = n.parentElement;
      if (!p || (p.closest && p.closest(SKIP))) return NodeFilter.FILTER_REJECT;
      return NodeFilter.FILTER_ACCEPT;
    } }, false);
    var n, ni = 0, index = new Map();
    while ((n = w.nextNode())) {
      var raw = n.textContent;
      var blk = blockOf(n, body);
      var brBefore = n.previousSibling && n.previousSibling.nodeName === 'BR';
      if (norm && (blk !== lastBlock || brBefore) && norm[norm.length - 1] !== ' ') { norm += ' '; map.push([ni, -0.5]); }
      for (var i = 0; i < raw.length; i++) {
        var c = normChar(raw[i]);
        if (!c) continue;
        if (c === ' ' && (norm === '' || norm[norm.length - 1] === ' ')) continue;
        norm += c; map.push([ni, i]);
      }
      if (raw.length) lastBlock = blk;
      nodes.push(n); index.set(n, ni); ni++;
    }
    return { nodes: nodes, norm: norm, map: map, index: index };
  }

  function cmp(a, b) { return a[0] !== b[0] ? a[0] - b[0] : a[1] - b[1]; }

  // First map index whose position is at or after p.
  function lowerBound(map, p) {
    var lo = 0, hi = map.length;
    while (lo < hi) { var mid = (lo + hi) >> 1; if (cmp(map[mid], p) < 0) lo = mid + 1; else hi = mid; }
    return lo;
  }

  // A DOM boundary as a [node index, offset] position in the model: inside a modelled text node
  // it is that node and offset; anywhere else (an element boundary, a skipped node) it is the
  // start of the first modelled text node at or after it.
  function pos(m, d, container, offset) {
    if (container.nodeType === 3) {
      var ni = m.index.get(container);
      if (ni !== undefined) return [ni, offset];
    }
    try {
      var r0 = d.createRange(); r0.setStart(container, offset); r0.collapse(true);
      for (var i = 0; i < m.nodes.length; i++) if (r0.comparePoint(m.nodes[i], 0) >= 0) return [i, 0];
    } catch (e) {}
    return [m.nodes.length, 0];
  }

  function realStart(m, s) { while (s < m.map.length && m.map[s][1] < 0) s++; return s; }
  function realEnd(m, e) { while (e > 0 && m.map[e - 1][1] < 0) e--; return e; }

  function rangeOf(d, m, s, e) {
    s = realStart(m, s); e = realEnd(m, e);
    if (e <= s) return null;
    var a = m.map[s], b = m.map[e - 1];
    var r = d.createRange();
    r.setStart(m.nodes[a[0]], a[1]);
    r.setEnd(m.nodes[b[0]], b[1] + 1);
    return r;
  }

  function indicesOf(d, m, r) {
    return [lowerBound(m.map, pos(m, d, r.startContainer, r.startOffset)),
            lowerBound(m.map, pos(m, d, r.endContainer, r.endOffset))];
  }

  function isDark(d) {
    try {
      var win = d.defaultView, els = [d.body, d.documentElement];
      for (var i = 0; i < els.length; i++) {
        if (!els[i] || !win) continue;
        var mm = String(win.getComputedStyle(els[i]).backgroundColor || '').match(/rgba?\(([^)]+)\)/);
        if (!mm) continue;
        var p = mm[1].split(',').map(parseFloat);
        if (p.length > 3 && p[3] === 0) continue;
        return (0.2126 * p[0] + 0.7152 * p[1] + 0.0722 * p[2]) / 255 < 0.45;
      }
    } catch (e) {}
    return false;
  }

  function clear(d) {
    var win = d.defaultView;
    if (win && win.CSS && win.CSS.highlights) {
      try { win.CSS.highlights['delete']('ledger-mark'); win.CSS.highlights['delete']('ledger-mark-noted'); } catch (e) {}
    }
    for (var i = 0; i < spans.length; i++) {
      var sp = spans[i]; if (!sp.parentNode) continue;
      var parent = sp.parentNode;
      while (sp.firstChild) parent.insertBefore(sp.firstChild, sp);
      parent.removeChild(sp);
      if (parent.normalize) parent.normalize();
    }
    spans = []; painted = [];
  }

  function score(m, at, len, prefix, suffix) {
    var sc = 0, i;
    var before = m.norm.slice(Math.max(0, at - prefix.length), at);
    for (i = 1; i <= Math.min(before.length, prefix.length); i++) {
      if (before[before.length - i] !== prefix[prefix.length - i]) break; sc++;
    }
    var after = m.norm.slice(at + len, at + len + suffix.length);
    for (i = 0; i < Math.min(after.length, suffix.length); i++) { if (after[i] !== suffix[i]) break; sc++; }
    return sc;
  }

  function find(m, needle, prefix, suffix) {
    var hits = [], at = m.norm.indexOf(needle);
    while (at >= 0 && hits.length < 64) { hits.push(at); at = m.norm.indexOf(needle, at + 1); }
    if (!hits.length) {
      // Same passage, different capitals (a title-cased heading) — still a full match.
      var low = m.norm.toLowerCase(), nl = needle.toLowerCase();
      at = low.indexOf(nl);
      while (at >= 0 && hits.length < 64) { hits.push(at); at = low.indexOf(nl, at + 1); }
    }
    if (!hits.length) return -1;
    if (hits.length === 1) return hits[0];
    var best = hits[0], bestScore = -1, p = normText(prefix || ''), s = normText(suffix || '');
    for (var i = 0; i < hits.length; i++) {
      var sc = score(m, hits[i], needle.length, p, s);
      if (sc > bestScore) { bestScore = sc; best = hits[i]; }
    }
    return best;
  }

  function drawStyle(d, dark) {
    var line = dark ? '#ffffff' : '#000000';
    var css = '::highlight(ledger-mark){background-color:rgba(255,214,10,.3);color:inherit;' +
      'text-decoration:underline solid 3px ' + line + ';text-underline-offset:2px}' +
      '::highlight(ledger-mark-noted){background-color:rgba(255,214,10,.3);color:inherit;' +
      'text-decoration:underline double 3px ' + line + ';text-underline-offset:2px}';
    var st = d.getElementById('__ledger-marks-style');
    if (!st) { st = d.createElement('style'); st.id = '__ledger-marks-style'; (d.head || d.documentElement).appendChild(st); }
    if (st.textContent !== css) st.textContent = css;
  }

  function paint(list) {
    var d = document, unplaced = [];
    try {
      clear(d);
      var m = build(d);
      if (!m.norm) return JSON.stringify((list || []).map(function (x) { return x.id; }));
      var win = d.defaultView, useHl = !!(win && win.CSS && win.CSS.highlights && win.Highlight);
      var dark = isDark(d), plain = [], noted = [];
      for (var i = 0; i < (list || []).length; i++) {
        var mk = list[i], needle = normText(mk.exact);
        if (!needle) { unplaced.push(mk.id); continue; }
        var at = find(m, needle, mk.prefix, mk.suffix);
        if (at < 0) { unplaced.push(mk.id); continue; }
        var s = at, e = at + needle.length, dup = false;
        for (var j = 0; j < painted.length; j++) if (s < painted[j].e && painted[j].s < e) { dup = true; break; }
        if (dup) continue;   // one highlight per passage — the first (a mark, the app sends marks first) stands
        var r = rangeOf(d, m, s, e);
        if (!r) { unplaced.push(mk.id); continue; }
        painted.push({ id: mk.id, range: r, noted: !!mk.noted, s: s, e: e });
        (mk.noted ? noted : plain).push(r);
      }
      if (useHl) {
        drawStyle(d, dark);
        if (plain.length) win.CSS.highlights.set('ledger-mark', new win.Highlight(plain[0]));
        for (var a = 1; a < plain.length; a++) win.CSS.highlights.get('ledger-mark').add(plain[a]);
        if (noted.length) win.CSS.highlights.set('ledger-mark-noted', new win.Highlight(noted[0]));
        for (var b = 1; b < noted.length; b++) win.CSS.highlights.get('ledger-mark-noted').add(noted[b]);
      } else {
        // One span per touched text-node segment, the LAST passage in the page first, so the
        // offsets of every earlier one stay valid while the text nodes split.
        var line = dark ? '#fff' : '#000';
        var order = painted.slice().sort(function (x, y) { return y.s - x.s; });
        for (var k = 0; k < order.length; k++) {
          var pm = order[k];
          var segs = [], sa = m.map[realStart(m, pm.s)], eb = m.map[realEnd(m, pm.e) - 1];
          for (var ni = sa[0]; ni <= eb[0]; ni++) {
            var node = m.nodes[ni], from = ni === sa[0] ? sa[1] : 0, to = ni === eb[0] ? eb[1] + 1 : node.textContent.length;
            if (to > from) segs.push([node, from, to]);
          }
          for (var g = segs.length - 1; g >= 0; g--) {
            var seg = d.createRange(); seg.setStart(segs[g][0], segs[g][1]); seg.setEnd(segs[g][0], segs[g][2]);
            var span = d.createElement('span');
            span.setAttribute('data-ledger-mark', pm.id);
            span.style.backgroundColor = 'rgba(255,214,10,.3)';
            span.style.borderBottom = (pm.noted ? '4px double ' : '3px solid ') + line;
            try { seg.surroundContents(span); spans.push(span); } catch (e2) {}
          }
        }
        // Wrapping split the text nodes the ranges pointed into: each passage's range is re-laid
        // over its own spans, so hit-testing, overlap and jump read exactly what is drawn.
        for (var q = 0; q < painted.length; q++) {
          var mine = spans.filter(function (x) { return x.getAttribute('data-ledger-mark') === painted[q].id; });
          if (!mine.length) continue;
          var nr = d.createRange();
          nr.setStart(mine[0], 0);
          nr.setEnd(mine[mine.length - 1], mine[mine.length - 1].childNodes.length);
          painted[q].range = nr;
        }
      }
    } catch (e) {}
    return JSON.stringify(unplaced);
  }

  function liveRange() {
    var s = window.getSelection ? window.getSelection() : null;
    if (s && s.rangeCount && !s.isCollapsed && String(s).trim()) return s.getRangeAt(0).cloneRange();
    if (window.__ledgerSelRange && Date.now() - (window.__ledgerSelAt || 0) < FRESH_MS) return window.__ledgerSelRange;
    return null;
  }

  function linksIn(r) {
    var out = [], seen = {};
    try {
      var as = r.cloneContents().querySelectorAll('a[href]');
      for (var i = 0; i < as.length; i++) {
        var h = as[i].href || ''; if (!/^https?:/.test(h) || seen[h]) continue; seen[h] = 1;
        var l = (as[i].textContent || '').trim().replace(/\s+/g, ' ');
        out.push(l && l !== h ? (l + ' \u2192 ' + h) : h);
      }
      if (!out.length) {
        var n = r.commonAncestorContainer; if (n && n.nodeType === 3) n = n.parentElement;
        var a = n && n.closest ? n.closest('a[href]') : null;
        if (a && /^https?:/.test(a.href)) out.push(a.href);
      }
    } catch (e) {}
    return out;
  }

  function capture() {
    try {
      var d = document, r = liveRange();
      if (!r) return null;
      var m = build(d);
      var ix = indicesOf(d, m, r), s = ix[0], e = ix[1];
      while (s < e && m.norm[s] === ' ') s++;
      while (e > s && m.norm[e - 1] === ' ') e--;
      if (e <= s) return null;
      var overlap = '';
      for (var i = 0; i < painted.length; i++) {
        var p = indicesOf(d, m, painted[i].range);
        if (s < p[1] && p[0] < e) { overlap = painted[i].id; break; }
      }
      return JSON.stringify({
        exact: m.norm.slice(s, e),
        prefix: m.norm.slice(Math.max(0, s - 32), s),
        suffix: m.norm.slice(e, e + 32),
        fraction: m.norm.length ? s / m.norm.length : 0,
        overlapId: overlap,
        links: linksIn(r)
      });
    } catch (e) { return null; }
  }

  function hitAt(x, y, target) {
    try {
      if (!target && spans.length && document.elementFromPoint) target = document.elementFromPoint(x, y);
      if (target && target.closest) {
        var sp = target.closest('[data-ledger-mark]');
        if (sp) return sp.getAttribute('data-ledger-mark') || '';
      }
      for (var i = 0; i < painted.length; i++) {
        var rs = painted[i].range.getClientRects();
        for (var j = 0; j < rs.length; j++) {
          var b = rs[j];
          if (x >= b.left && x <= b.right && y >= b.top - 2 && y <= b.bottom + 2) return painted[i].id;
        }
      }
    } catch (e) {}
    return '';
  }

  function jump(id) {
    for (var i = 0; i < painted.length; i++) {
      if (painted[i].id !== id) continue;
      try {
        var b = painted[i].range.getBoundingClientRect();
        window.scrollTo({ top: Math.max(0, b.top + window.scrollY - window.innerHeight / 3), left: 0, behavior: 'instant' });
        return true;
      } catch (e) { return false; }
    }
    return false;
  }

  document.addEventListener('click', function (ev) {
    try {
      var sel = window.getSelection ? window.getSelection() : null;
      if (sel && !sel.isCollapsed && String(sel).trim()) return;
      var t = ev.target;
      if (t && t.closest && t.closest('a[href],button,input,select,textarea,video,audio,iframe,label')) return;
      var id = hitAt(ev.clientX, ev.clientY, t);
      if (!id) return;
      ev.preventDefault(); ev.stopPropagation();
      window.location.href = 'ledger-mark://' + encodeURIComponent(id);
    } catch (e) {}
  }, true);

  // The context either side of a drawn passage — an old pick adopted into a mark takes it, so
  // its next paint can tell repeated passages apart.
  function contextOf(id) {
    try {
      var d = document, m = build(d);
      for (var i = 0; i < painted.length; i++) {
        if (painted[i].id !== id) continue;
        var ix = indicesOf(d, m, painted[i].range);
        return JSON.stringify({ prefix: m.norm.slice(Math.max(0, ix[0] - 32), ix[0]),
          suffix: m.norm.slice(ix[1], ix[1] + 32), fraction: m.norm.length ? ix[0] / m.norm.length : 0 });
      }
    } catch (e) {}
    return null;
  }

  window.__ledgerMarks = {
    paint: paint, capture: capture, jump: jump, contextOf: contextOf,
    hitAt: function (x, y) { return hitAt(x, y, null); },
    clear: function () { clear(document); },
    normText: normText
  };

  // Marks seeded into the page's head paint at the first parse, before the first frame where the
  // engine allows — Feeds' own text and Reader view cost no extra e-ink refresh (critique #8).
  var seed = window.__ledgerMarksSeed;
  if (seed && seed.length) {
    var go = function () { window.__ledgerMarksUnplaced = paint(seed); };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', go); else go();
  }
})();
