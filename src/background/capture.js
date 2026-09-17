// 엔드포인트 탐색 도우미.
//
// LMS 페이지에서 오가는 fetch/XHR 중 "날짜처럼 생긴 필드가 든 JSON"만 골라 기록한다.
// 개발자도구 Network 탭을 직접 뒤지는 대신, 옵션 화면에서 후보 목록을 바로 보게 하려는 것.

import { STATE_KEYS } from '../lib/storage.js';

const MAX_ENTRIES = 40;

export async function registerCaptureScripts(settings) {
  if (!settings.origin) throw new Error('LMS 주소를 먼저 입력하세요.');
  const matches = [`${new URL(settings.origin).origin}/*`];

  await unregisterCaptureScripts();
  await chrome.scripting.registerContentScripts([
    {
      id: 'capture-main',
      js: ['src/content/capture-main.js'],
      matches,
      runAt: 'document_start',
      world: 'MAIN',
    },
    {
      id: 'capture-relay',
      js: ['src/content/capture-relay.js'],
      matches,
      runAt: 'document_start',
      world: 'ISOLATED',
    },
  ]);
  return true;
}

export async function unregisterCaptureScripts() {
  const existing = await chrome.scripting.getRegisteredContentScripts().catch(() => []);
  const ids = existing.map((s) => s.id).filter((id) => id.startsWith('capture-'));
  if (ids.length) await chrome.scripting.unregisterContentScripts({ ids });
  return true;
}

export async function recordCapture(entry, sender) {
  if (!entry?.url) return false;

  const store = await chrome.storage.local.get(STATE_KEYS.captured);
  const list = store[STATE_KEYS.captured] || [];

  // 같은 엔드포인트는 최신 것 하나만 남긴다. 쿼리스트링은 제외하고 비교.
  const bare = entry.url.split('?')[0];
  const next = [
    {
      url: entry.url,
      method: entry.method || 'GET',
      dateFields: entry.dateFields || [],
      arrayPath: entry.arrayPath || '',
      sampleKeys: entry.sampleKeys || [],
      sample: entry.sample,
      pageUrl: sender?.tab?.url || '',
      at: Date.now(),
    },
    ...list.filter((e) => e.url.split('?')[0] !== bare),
  ].slice(0, MAX_ENTRIES);

  await chrome.storage.local.set({ [STATE_KEYS.captured]: next });
  return true;
}
