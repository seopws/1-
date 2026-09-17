// offscreen 문서: HTML 문자열을 받아 선택자대로 긁어서 돌려준다. 여기서만 DOM을 쓴다.

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg?.target !== 'offscreen' || msg.type !== 'parse-html') return false;

  try {
    sendResponse({ ok: true, rows: parse(msg.payload.html, msg.payload.spec) });
  } catch (err) {
    sendResponse({ ok: false, error: String(err?.message || err) });
  }
  return true;
});

function parse(html, spec) {
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const rows = [...doc.querySelectorAll(spec.rowSelector)];

  return rows.map((row) => {
    const out = {};
    for (const [field, selector] of Object.entries(spec.fields || {})) {
      out[field] = selector ? text(row.querySelector(selector)) : text(row);
    }
    const link = row.querySelector(spec.linkSelector || 'a');
    const href = link?.getAttribute('href') || '';
    out.href = href ? new URL(href, spec.baseUrl).href : '';
    return out;
  });
}

function text(el) {
  return el ? el.textContent.replace(/\s+/g, ' ').trim() : '';
}
