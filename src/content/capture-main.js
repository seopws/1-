// MAIN world: 페이지의 fetch/XHR를 가로채서 "과제 마감일이 들어있을 법한 JSON" 응답을 찾아낸다.
// 응답 본문은 복사본만 읽고 원래 흐름은 그대로 흘려보낸다 (페이지 동작에 영향 없음).

(() => {
  if (window.__gwaje_capture_installed) return;
  window.__gwaje_capture_installed = true;

  const DATE_KEY = /(due|end|close|deadline|submit|expire|start|open)[_-]?(at|date|time|dt)?$|마감|기한/i;
  const DATE_VALUE = /^\d{4}[-./]\d{1,2}[-./]\d{1,2}([T ]\d{2}:\d{2})?/;

  function analyze(json) {
    const found = { dateFields: new Set(), arrayPath: '', sampleKeys: [], sample: null };

    const visit = (node, path, depth) => {
      if (depth > 4 || node == null) return;

      if (Array.isArray(node)) {
        const first = node.find((x) => x && typeof x === 'object');
        if (first && !found.arrayPath) {
          found.arrayPath = path;
          found.sampleKeys = Object.keys(first).slice(0, 40);
          found.sample = first;
        }
        node.slice(0, 3).forEach((x) => visit(x, path ? `${path}[]` : '[]', depth + 1));
        return;
      }

      if (typeof node !== 'object') return;

      for (const [k, v] of Object.entries(node)) {
        const child = path ? `${path}.${k}` : k;
        // 값이 날짜처럼 생겼거나, 키 이름이 마감일스러운데 값이 비어있지 않으면 후보로 본다.
        if (typeof v === 'string' && v && (DATE_VALUE.test(v) || DATE_KEY.test(k))) {
          found.dateFields.add(k);
        }
        visit(v, child, depth + 1);
      }
    };

    visit(json, '', 0);
    return { ...found, dateFields: [...found.dateFields] };
  }

  function report(url, method, text) {
    if (!text || text.length > 2_000_000) return;
    let json;
    try {
      json = JSON.parse(text.replace(/^\s*while\s*\(1\);?/, ''));
    } catch {
      return;
    }
    const info = analyze(json);
    if (!info.dateFields.length) return; // 마감일 같은 게 없으면 관심 없음

    window.postMessage(
      {
        __gwajeCapture: true,
        entry: {
          url: new URL(url, location.href).href,
          method,
          dateFields: info.dateFields,
          arrayPath: info.arrayPath,
          sampleKeys: info.sampleKeys,
          sample: info.sample,
        },
      },
      '*'
    );
  }

  const origFetch = window.fetch;
  window.fetch = async function (...args) {
    const res = await origFetch.apply(this, args);
    try {
      const url = typeof args[0] === 'string' ? args[0] : args[0]?.url;
      const method = (args[1]?.method || args[0]?.method || 'GET').toUpperCase();
      res.clone().text().then((t) => report(url, method, t)).catch(() => {});
    } catch {}
    return res;
  };

  const origOpen = XMLHttpRequest.prototype.open;
  const origSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (method, url, ...rest) {
    this.__gwaje = { method, url };
    return origOpen.call(this, method, url, ...rest);
  };
  XMLHttpRequest.prototype.send = function (...args) {
    this.addEventListener('load', () => {
      try {
        if (this.responseType === '' || this.responseType === 'text') {
          report(this.__gwaje?.url, this.__gwaje?.method, this.responseText);
        }
      } catch {}
    });
    return origSend.apply(this, args);
  };
})();
