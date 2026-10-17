package com.toolsboox.plugin.feeds.ot

/**
 * The read-aloud FOLLOW-ALONG, one script for every reading web view (the book reader's page,
 * the feeds pane's article and letter): `window.__ledgerFollow(sentence)` lights the sentence the
 * voice is on, and `window.__ledgerFollow("")` clears it.
 *
 * THE MATCH RUNS OVER THE WHOLE BODY'S TEXT, not one text node at a time (Michael, 09-11, of the
 * iOS twin: "it's not really doing it beyond the first paragraph" — and this script had the same
 * shape). A first paragraph is usually one plain text node; the next sentence with a link, an
 * emphasis or a curly quote in it is three, and a probe that had to sit inside ONE node never
 * matched again. So: the text nodes are concatenated into one normalised string (curly quotes
 * straightened, whitespace collapsed, NBSP, with a map back to (node, raw offset)); the spoken
 * sentence is searched there — forward from the last hit first, the probe shrinking when it
 * must — and the hit becomes a run of node ranges that may start in one node and end in another.
 *
 * The mark is `CSS.highlights` where the WebView has it (no DOM change), else one outlined
 * `<span>` per touched text-node segment, unwrapped before the next sentence — a quiet swap per
 * sentence, which is what e-ink wants; never a user mark. Scrolls only when the sentence is off
 * screen. Foliate's book lives in an iframe, so the document is looked up each call.
 */
object TtsFollowJs {
    const val SCRIPT = """
(function(){
  if (window.__ledgerFollow) return;
  var marks = [];
  var lastAt = 0, lastBody = null;
  function doc(){ var f=document.querySelector('iframe'); try { return (f&&f.contentDocument)||document; } catch(e){ return document; } }
  function normChar(c){
    if (c === '‘' || c === '’' || c === '‚' || c === '′') return "'";
    if (c === '“' || c === '”' || c === '„' || c === '″') return '"';
    if (c === ' ' || c === ' ' || c === ' ' || /\s/.test(c)) return ' ';
    return c;
  }
  // DARK PAGES TOO (review, Michael 2026-10-11): articles can be Black or Charcoal now, and a
  // book could always be Black — a black band and a black outline vanish there. On a light page
  // the mark is exactly what it was; on a dark one it is the same band and outline in white.
  function isDarkPage(d){
    try {
      var win = d.defaultView, els = [d.body, d.documentElement];
      for (var i = 0; i < els.length; i++) {
        if (!els[i] || !win) continue;
        var m = String(win.getComputedStyle(els[i]).backgroundColor || '').match(/rgba?\(([^)]+)\)/);
        if (!m) continue;
        var p = m[1].split(',').map(parseFloat);
        if (p.length > 3 && p[3] === 0) continue;
        return (0.2126 * p[0] + 0.7152 * p[1] + 0.0722 * p[2]) / 255 < 0.45;
      }
    } catch(e){}
    return false;
  }
  function clear(d){
    if (d.defaultView && d.defaultView.CSS && d.defaultView.CSS.highlights) { try { d.defaultView.CSS.highlights['delete']('tts-follow'); } catch(e){} }
    for (var i = 0; i < marks.length; i++) {
      var m = marks[i]; if (!m.parentNode) continue;
      var t = d.createTextNode(m.textContent); m.parentNode.replaceChild(t, m);
      if (t.parentNode && t.parentNode.normalize) t.parentNode.normalize();
    }
    marks = [];
  }
  window.__ledgerFollow = function(sentence){
    try {
      var d = doc();
      clear(d);
      var needle = '';
      var raw0 = String(sentence || '');
      for (var q = 0; q < raw0.length; q++) { var ch = normChar(raw0[q]); if (ch === ' ' && needle.slice(-1) === ' ') continue; needle += ch; }
      needle = needle.trim();
      if (!needle) return;
      var nodes = [], norm = '', map = [];
      var w = d.createTreeWalker(d.body, NodeFilter.SHOW_TEXT, { acceptNode: function(n){
        var p = n.parentElement; if (!p) return NodeFilter.FILTER_REJECT;
        var t = p.tagName; if (t === 'SCRIPT' || t === 'STYLE' || t === 'NOSCRIPT' || t === 'TEMPLATE') return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT; } }, false);
      var n, ni = 0;
      while ((n = w.nextNode())) {
        var raw = n.textContent;
        for (var i = 0; i < raw.length; i++) { var c = normChar(raw[i]); if (c === ' ' && norm.slice(-1) === ' ') continue; norm += c; map.push([ni, i]); }
        nodes.push(n); ni++;
      }
      if (!norm) return;
      if (lastBody !== d.body) { lastBody = d.body; lastAt = 0; }
      var probes = [60, 40, 24, 14], at = -1, plen = 0;
      for (var pi = 0; pi < probes.length && at < 0; pi++) {
        var probe = needle.slice(0, probes[pi]); if (probe.length < 8 && pi < probes.length - 1) continue;
        at = norm.indexOf(probe, lastAt); if (at < 0) at = norm.indexOf(probe); plen = probe.length;
      }
      if (at < 0) return;
      var end = Math.min(norm.length - 1, at + Math.max(plen, needle.length) - 1);
      var s0 = map[at], e0 = map[end];
      lastAt = at + plen;
      var win = d.defaultView;
      var dark = isDarkPage(d);
      if (win && win.CSS && win.CSS.highlights && win.Highlight) {
        // Re-checked each sentence: the theme can change mid-read (Aa Type, live).
        var css = '::highlight(tts-follow){background:' + (dark ? 'rgba(255,255,255,.3)' : 'rgba(0,0,0,.18)') + ';color:inherit}';
        var st = d.getElementById('__ledger-tts-follow');
        if (!st) { st = d.createElement('style'); st.id = '__ledger-tts-follow'; d.head.appendChild(st); }
        if (st.textContent !== css) st.textContent = css;
        var r = d.createRange(); r.setStart(nodes[s0[0]], s0[1]); r.setEnd(nodes[e0[0]], e0[1] + 1);
        win.CSS.highlights.set('tts-follow', new win.Highlight(r));
      } else {
        // One outlined span per touched text-node segment — a range across elements can't be
        // surrounded in one go, and a partial wrap is worse than none.
        for (var k = s0[0]; k <= e0[0]; k++) {
          var node = nodes[k]; var from = (k === s0[0]) ? s0[1] : 0; var to = (k === e0[0]) ? e0[1] + 1 : node.textContent.length;
          if (to <= from) continue;
          var rr = d.createRange(); rr.setStart(node, from); rr.setEnd(node, to);
          var span = d.createElement('span'); span.style.outline = '2px solid ' + (dark ? '#fff' : '#000'); span.style.outlineOffset = '1px';
          try { rr.surroundContents(span); marks.push(span); } catch(e) {}
        }
      }
      var el = nodes[s0[0]].parentElement;
      if (el && el.getBoundingClientRect) {
        var b = el.getBoundingClientRect(); var vh = (win && win.innerHeight) || d.documentElement.clientHeight;
        if (b.top < 24 || b.bottom > vh - 24) el.scrollIntoView({ block: 'center' });
      }
    } catch(e){}
  };
})();
"""
}
