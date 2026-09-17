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

  // JSON을 기대했는데 HTML이 오면 십중팔구 로그인 페이지로 리다이렉트된 것.
  const ct = res.headers.get('content-type') || '';
  if (ct.includes('text/html') || /^\s*<(!doctype|html)/i.test(text)) {
    throw new NotLoggedInError(url);
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
