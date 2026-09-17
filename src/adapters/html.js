// HTML 스크래핑 어댑터 (최후의 수단).
//
// API를 끝내 못 찾은 경우, 과제 목록 "페이지"를 그대로 받아서 DOM에서 긁는다.
// MV3 서비스워커에는 DOMParser가 없어서, 파싱은 offscreen 문서에 맡긴다.

import { getText, joinUrl } from '../lib/http.js';
import { normalize } from '../lib/model.js';
import { parseHtmlInOffscreen } from '../background/offscreen-client.js';

const SOURCE = 'html';

export const htmlAdapter = {
  id: 'html',
  label: 'HTML 스크래핑 (최후의 수단)',

  async detect(origin, signal, settings) {
    return Boolean(settings?.html?.listUrl && settings?.html?.rowSelector);
  },

  async fetchAll(origin, opts) {
    const cfg = opts.settings.html;
    if (!cfg.listUrl || !cfg.rowSelector) {
      throw new Error('옵션에서 "목록 페이지 URL"과 "행 선택자"를 먼저 입력하세요.');
    }

    const url = joinUrl(origin, cfg.listUrl);
    const { text } = await getText(url, { signal: opts.signal });

    const rows = await parseHtmlInOffscreen(text, {
      rowSelector: cfg.rowSelector,
      fields: {
        title: cfg.titleSelector,
        dueAt: cfg.dueSelector,
        courseName: cfg.courseSelector,
      },
      linkSelector: cfg.linkSelector || 'a',
      baseUrl: url,
    });

    const items = rows
      .map((r) =>
        normalize(
          {
            id: `html:${r.href || r.title}`,
            source: SOURCE,
            title: r.title,
            courseName: r.courseName,
            dueAt: cleanDueText(r.dueAt),
            url: r.href || '',
          },
          { source: SOURCE }
        )
      )
      .filter((a) => a.title && a.title !== '(제목 없음)');

    if (!items.length) throw new Error('선택자에 걸리는 행이 없습니다. 행 선택자를 다시 확인하세요.');
    return items;
  },
};

/** "마감: 2026-03-14 23:59 까지" 같은 문장에서 날짜만 뽑는다. */
function cleanDueText(text) {
  if (!text) return null;
  const m = String(text).match(/\d{4}[-./]\d{1,2}[-./]\d{1,2}(?:[ T]\d{1,2}:\d{2}(?::\d{2})?)?/);
  return m ? m[0] : null;
}

export { cleanDueText };
