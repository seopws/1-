// LMS에 붙을 때 쓰는 fetch 헬퍼.
// 핵심: credentials:'include' 로 브라우저에 이미 있는 로그인 세션 쿠키를 그대로 태운다.
// 그래서 확장 프로그램이 학번/비밀번호를 알 필요가 없다.

export class NotLoggedInError extends Error {
  constructor(url) {
    super('LMS 로그인 세션이 없습니다. LMS에 로그인한 뒤 다시 시도하세요.');
    this.name = 'NotLoggedInError';
    this.url = url;
  }
}

/**
 * JSON을 기대한 자리에 HTML이 온 경우.
 *
 * 로그인 만료와는 다르다. 없는 경로에 SPA 껍데기를 돌려주는 서버가 흔한데,
 * 그걸 로그인 만료로 착각하면 "이 경로는 없다"가 "세션이 끊겼다"로 둔갑한다.
 * 후보 경로를 찔러보는 코드에서는 이 차이가 치명적이다.
 */
export class NotJsonError extends Error {
  constructor(url) {
    super(`JSON이 아닌 응답입니다 — ${url}`);
    this.name = 'NotJsonError';
    this.url = url;
  }
}

export class HttpError extends Error {
  constructor(status, url, body) {
    super(`HTTP ${status} — ${url}`);
    this.name = 'HttpError';
    this.status = status;
    this.url = url;
    this.body = body;
  }
}

const DEFAULT_HEADERS = {
  Accept: 'application/json, text/plain, */*',
  'X-Requested-With': 'XMLHttpRequest',
};

export async function getJSON(url, { signal, headers } = {}) {
  const res = await fetch(url, {
    credentials: 'include',
    redirect: 'follow',
    headers: { ...DEFAULT_HEADERS, ...headers },
    signal,
  });

  if (res.status === 401 || res.status === 403) throw new NotLoggedInError(url);

  const text = await res.text();

  if (!res.ok) throw new HttpError(res.status, url, text.slice(0, 500));

  const ct = res.headers.get('content-type') || '';
  if (ct.includes('text/html') || /^\s*<(!doctype|html)/i.test(text)) {
    // 로그인 페이지로 튕긴 것인지, 그냥 없는 경로인지 구분한다.
    // 리다이렉트됐거나 최종 주소가 로그인처럼 생겼으면 세션 문제로 본다.
    const looksLikeLogin = res.redirected || /\/(login|signin|sso|auth)/i.test(res.url || '');
    throw looksLikeLogin ? new NotLoggedInError(url) : new NotJsonError(url);
  }

  // Canvas 계열은 JSON 앞에 while(1); 를 붙여 보낸다.
  const clean = text.replace(/^\s*while\s*\(1\);?/, '');

  let data;
  try {
    data = JSON.parse(clean);
  } catch {
    throw new HttpError(res.status, url, clean.slice(0, 200));
  }

  return { data, link: res.headers.get('Link') || '', res };
}

export async function getText(url, { signal, headers } = {}) {
  const res = await fetch(url, {
    credentials: 'include',
    redirect: 'follow',
    headers: { Accept: 'text/html,*/*', ...headers },
    signal,
  });
  if (res.status === 401 || res.status === 403) throw new NotLoggedInError(url);
  if (!res.ok) throw new HttpError(res.status, url, '');
  return { text: await res.text(), finalUrl: res.url };
}

/** Link 헤더의 rel="next" 를 따라가며 배열 응답을 전부 모은다. (최대 maxPages) */
export async function getAllPages(url, { signal, maxPages = 10 } = {}) {
  const out = [];
  let next = url;
  for (let i = 0; i < maxPages && next; i++) {
    const { data, link } = await getJSON(next, { signal });
    if (Array.isArray(data)) out.push(...data);
    else if (data && Array.isArray(data.items)) out.push(...data.items);
    else if (data) out.push(data);
    next = parseNextLink(link);
  }
  return out;
}

export function parseNextLink(linkHeader) {
  if (!linkHeader) return null;
  for (const part of linkHeader.split(',')) {
    const m = part.match(/<([^>]+)>\s*;\s*rel="?next"?/i);
    if (m) return m[1];
  }
  return null;
}

/** 'data.items[0].due_at' 같은 경로로 중첩 객체에서 값을 꺼낸다. */
export function pick(obj, path) {
  if (!path) return undefined;
  let cur = obj;
  for (const seg of String(path).replace(/\[(\d+)\]/g, '.$1').split('.')) {
    if (cur == null) return undefined;
    if (seg === '') continue;
    cur = cur[seg];
  }
  return cur;
}

/** 실패해도 전체를 죽이지 않고 null을 돌려주는 래퍼. 어댑터가 여러 엔드포인트를 찔러볼 때 쓴다. */
export async function tryOr(fn, fallback = null) {
  try {
    return await fn();
  } catch (err) {
    if (err instanceof NotLoggedInError) throw err; // 로그인 문제는 삼키면 안 된다.
    return fallback;
  }
}

export function joinUrl(origin, path) {
  if (/^https?:\/\//i.test(path)) return path;
  return `${String(origin).replace(/\/+$/, '')}/${String(path).replace(/^\/+/, '')}`;
}
