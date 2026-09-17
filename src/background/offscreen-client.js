// 서비스워커에는 DOMParser가 없다. offscreen 문서를 하나 띄워서 HTML 파싱을 위임한다.

const OFFSCREEN_PATH = 'src/background/offscreen.html';
let creating = null;

async function ensureOffscreen() {
  const existing = await chrome.runtime.getContexts({
    contextTypes: ['OFFSCREEN_DOCUMENT'],
    documentUrls: [chrome.runtime.getURL(OFFSCREEN_PATH)],
  });
  if (existing.length) return;

  if (!creating) {
    creating = chrome.offscreen
      .createDocument({
        url: OFFSCREEN_PATH,
        reasons: ['DOM_PARSER'],
        justification: 'LMS 과제 목록 페이지의 HTML을 파싱하기 위해 DOMParser가 필요합니다.',
      })
      .finally(() => {
        creating = null;
      });
  }
  await creating;
}

export async function parseHtmlInOffscreen(html, spec) {
  await ensureOffscreen();
  const reply = await chrome.runtime.sendMessage({
    target: 'offscreen',
    type: 'parse-html',
    payload: { html, spec },
  });
  if (!reply || reply.ok !== true) {
    throw new Error(reply?.error || 'HTML 파싱에 실패했습니다.');
  }
  return reply.rows;
}
