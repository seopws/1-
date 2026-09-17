// 아이패드/아이폰용 북마클릿.
//
// iOS에는 크롬 확장이 없다. 대신 e-Class 페이지 안에서 직접 실행되는 스크립트를 쓴다.
// 페이지 컨텍스트에서 돌기 때문에 로그인 세션을 그대로 물려받고(동일 출처),
// 확장과 똑같이 학번/비밀번호를 알 필요가 없다.
//
// 어댑터·날짜 계산·ics 생성은 확장과 같은 모듈을 그대로 쓴다. 화면만 새로 그린다.

import { learningxAdapter, extractArray } from '../../src/adapters/learningx.js';
import { fetchCourses } from '../../src/adapters/canvas.js';
import {
  getJSON, joinUrl, tryOr, NotJsonError, HttpError, SELF_HEADER,
} from '../../src/lib/http.js';
import {
  dedupe, sortByDue, sortByPosted, ddayInfo, bucketOf, BUCKETS, TYPE_LABEL,
} from '../../src/lib/model.js';
import { toICS } from '../../src/lib/ics.js';

const HOST_ID = 'gwaje-hannune-root';
// 빌드가 커밋 SHA로 치환한다. 어느 버전이 돌고 있는지 진단에서 바로 보이게 하려는 것.
const BUILD_REF = '__BUILD_REF__';
const ECLASS_URL = 'https://eclass3.cau.ac.kr/';
// 서브도메인 경계를 지켜서 검사한다. /cau\.ac\.kr$/ 만 쓰면 evilcau.ac.kr 도 통과한다.
const ON_ECLASS = /(^|\.)cau\.ac\.kr$/;
const LOOK_AHEAD_DAYS = 60;
const LOOK_BACK_DAYS = 14;
const LOAD_TIMEOUT_MS = 45000;
const PROBE_TIMEOUT_MS = 8000;

const CSS = `
:host { all: initial; }
[hidden] { display: none !important; }
* { box-sizing: border-box; font-family: -apple-system, BlinkMacSystemFont, 'Apple SD Gothic Neo', 'Segoe UI', 'Noto Sans KR', sans-serif; }

.backdrop {
  position: fixed; inset: 0; z-index: 2147483647;
  background: rgba(16, 24, 40, 0.55);
  -webkit-backdrop-filter: blur(3px); backdrop-filter: blur(3px);
  display: flex; align-items: flex-end; justify-content: center;
}
.sheet {
  width: 100%; max-width: 720px; max-height: 92vh;
  background: #f7f8fa; color: #101828;
  border-radius: 16px 16px 0 0;
  display: flex; flex-direction: column;
  box-shadow: 0 -8px 40px rgba(0,0,0,0.3);
  animation: rise 0.22s ease-out;
}
@keyframes rise { from { transform: translateY(24px); opacity: 0; } }

header {
  display: flex; align-items: center; gap: 10px;
  padding: 14px 16px 12px; border-bottom: 1px solid #e4e7ec;
  background: #fff; border-radius: 16px 16px 0 0;
}
header h1 { margin: 0; font-size: 17px; font-weight: 700; flex: 1; letter-spacing: -0.01em; }
header .sub { font-size: 12px; color: #667085; font-weight: 400; display: block; margin-top: 2px; }

button {
  font: inherit; cursor: pointer; border: 1px solid #e4e7ec; background: #fff; color: #101828;
  border-radius: 9px; padding: 9px 13px; font-size: 14px; min-height: 40px;
  -webkit-tap-highlight-color: transparent;
}
button:active { background: #f2f4f7; }
button.icon { padding: 9px 12px; font-size: 16px; line-height: 1; min-width: 42px; }
button.primary { background: #3538cd; border-color: #3538cd; color: #fff; font-weight: 600; }

.stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; padding: 12px 16px 0; }
.stat { background: #fff; border: 1px solid #e4e7ec; border-radius: 10px; padding: 9px 10px; text-align: center; }
.stat .n { font-size: 20px; font-weight: 700; line-height: 1.15; font-variant-numeric: tabular-nums; }
.stat .k { font-size: 11px; color: #667085; margin-top: 1px; }
.stat.overdue .n { color: #b42318; }
.stat.urgent .n { color: #c4320a; }
.stat.soon .n { color: #b54708; }

.seg { display: flex; gap: 4px; margin: 12px 16px 0; padding: 3px; background: #eaecf0; border-radius: 11px; }
.seg button { flex: 1; border: none; background: transparent; color: #667085; font-size: 14px; font-weight: 600; border-radius: 8px; padding: 9px 6px; min-height: 40px; line-height: 1.2; }
.seg button[aria-selected='true'] { background: #fff; color: #101828; box-shadow: 0 1px 2px rgba(16,24,40,0.1); }
.seg .c { font-weight: 500; opacity: 0.65; margin-left: 4px; font-variant-numeric: tabular-nums; }
.tools { display: flex; gap: 8px; padding: 12px 16px; align-items: center; flex-wrap: wrap; }
input[type=search], select {
  font: inherit; font-size: 15px; padding: 9px 11px; min-height: 40px;
  border: 1px solid #e4e7ec; border-radius: 9px; background: #fff; color: #101828;
  -webkit-appearance: none; appearance: none;
}
input[type=search] { flex: 1 1 150px; min-width: 0; }
select { flex: 0 1 auto; max-width: 46%; }
.toggle { display: inline-flex; align-items: center; gap: 7px; font-size: 13px; color: #475467; white-space: nowrap; }
.toggle input { width: 19px; height: 19px; margin: 0; accent-color: #3538cd; }

main { flex: 1; overflow-y: auto; -webkit-overflow-scrolling: touch; padding: 0 16px 8px; }

.glabel { font-size: 12px; font-weight: 700; color: #667085; padding: 14px 2px 7px; display: flex; gap: 7px; }
.glabel .n { background: #eaecf0; color: #475467; border-radius: 99px; padding: 0 7px; font-size: 11px; }

.card { background: #fff; border: 1px solid #e4e7ec; border-radius: 11px; overflow: hidden; }
.row {
  display: grid; grid-template-columns: 78px 1fr; gap: 11px; align-items: center;
  padding: 12px 13px; border-top: 1px solid #f2f4f7;
  text-decoration: none; color: inherit;
}
.row:first-child { border-top: none; }
.row:active { background: #f7f8fa; }
.row.done { opacity: 0.48; }
.row .t { font-size: 14.5px; font-weight: 600; line-height: 1.35; }
.row .m { font-size: 12px; color: #667085; margin-top: 3px; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.dot { width: 8px; height: 8px; border-radius: 50%; flex: none; }
.tag { font-size: 11px; padding: 1px 7px; border-radius: 99px; background: #f2f4f7; color: #667085; }
.tag.ok { background: #f0f9ff; color: #026aa2; }

.dday {
  display: inline-flex; align-items: center; justify-content: center;
  padding: 4px 8px; border-radius: 99px; font-size: 12px; font-weight: 700;
  font-variant-numeric: tabular-nums; white-space: nowrap;
}
.dday.overdue { color: #b42318; background: #fef3f2; }
.dday.urgent  { color: #c4320a; background: #fff4ed; }
.dday.soon    { color: #b54708; background: #fffaeb; }
.dday.week    { color: #026aa2; background: #f0f9ff; }
.dday.later   { color: #475467; background: #f2f4f7; }
.dday.none    { color: #667085; background: #f2f4f7; }
.dday.posted  { color: #475467; background: #f2f4f7; font-weight: 600; }

footer { display: flex; gap: 8px; padding: 11px 16px calc(11px + env(safe-area-inset-bottom)); border-top: 1px solid #e4e7ec; background: #fff; }
footer button { flex: 1; }

.probe { padding: 14px 0 20px; }
.probe pre { margin: 0; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; line-height: 1.55; white-space: pre-wrap; word-break: break-all; background: #fff; border: 1px solid #e4e7ec; border-radius: 10px; padding: 12px; max-height: 46vh; overflow: auto; }
.probe-actions { display: flex; gap: 8px; margin-top: 12px; }
.probe-actions button { flex: 1; }
.msg { padding: 44px 24px; text-align: center; color: #667085; font-size: 14px; line-height: 1.7; }
.msg strong { color: #101828; display: block; margin-bottom: 6px; font-size: 15px; }
.msg .cta {
  display: inline-block; margin-top: 16px; padding: 11px 20px; min-height: 44px; line-height: 22px;
  background: #3538cd; color: #fff; border-radius: 9px; font-size: 14px; font-weight: 600;
  text-decoration: none; -webkit-tap-highlight-color: transparent;
}
.spin { display: inline-block; width: 22px; height: 22px; border: 2.5px solid #e4e7ec; border-top-color: #3538cd; border-radius: 50%; animation: sp 0.7s linear infinite; }
@keyframes sp { to { transform: rotate(360deg); } }

@media (prefers-color-scheme: dark) {
  .sheet { background: #14161a; color: #e7eaee; }
  header, .card, .stat, footer, button, input[type=search], select { background: #1c1f24; border-color: #2f343b; color: #e7eaee; }
  button:active, .row:active { background: #24282e; }
  button.primary { background: #a4bcfd; border-color: #a4bcfd; color: #14161a; }
  .row { border-top-color: #24282e; }
  .glabel, .stat .k, .row .m, .toggle, .msg { color: #98a2b3; }
  .glabel .n, .tag { background: #24282e; color: #98a2b3; }
  .probe pre { background: #1c1f24; border-color: #2f343b; color: #cfd4dc; }
  .msg strong { color: #e7eaee; }
  .msg .cta { background: #a4bcfd; color: #14161a; }
  .dday.overdue { color: #fda29b; background: #3a1c19; }
  .dday.urgent  { color: #fdb894; background: #3a2318; }
  .dday.soon    { color: #fdd87d; background: #382e14; }
  .dday.week    { color: #7cd4fd; background: #12283a; }
  .dday.later, .dday.none, .dday.posted { color: #cfd4dc; background: #262a30; }
  .seg { background: #24282e; }
  .seg button { color: #98a2b3; }
  .seg button[aria-selected='true'] { background: #12151a; color: #e7eaee; }
  .tag.ok { background: #12283a; color: #7cd4fd; }
}
`;

/** Canvas modules 처럼 항목이 한 겹 안에 들어있는 응답을 펴준다. */
function flatten(list) {
  const out = [];
  for (const entry of list) {
    if (entry && Array.isArray(entry.items)) out.push(...entry.items);
    else out.push(entry);
  }
  return out.length ? out : list;
}

const TITLE_KEYS = ['title', 'name', 'display_name', 'activity_name', 'subject'];

function titleOf(item) {
  if (!item || typeof item !== 'object') return '';

  const type = item.type || item.component_type || item.activity_type || item.plannable_type || '';
  for (const key of TITLE_KEYS) {
    if (typeof item[key] === 'string' && item[key]) {
      return type ? `${item[key]} (${type})` : item[key];
    }
  }

  // planner 항목처럼 제목이 한 겹 안에 들어있는 경우.
  for (const key of ['plannable', 'assignment', 'item', 'content']) {
    const inner = item[key];
    if (inner && typeof inner === 'object') {
      const found = TITLE_KEYS.map((k) => inner[k]).find((v) => typeof v === 'string' && v);
      if (found) return type ? `${found} (${type})` : found;
    }
  }

  return '';
}

/**
 * 응답이 없으면 영원히 기다리는 fetch는 화면을 멈춰 세운다.
 * 취소 가능한 신호를 만들어 넘기고, 끝나면 타이머를 반드시 정리한다.
 */
function withTimeout(ms, run) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), ms);
  return Promise.resolve(run(controller.signal)).finally(() => clearTimeout(timer));
}

/** 한 번에 limit개씩만 돌린다. 19개를 동시에 던지면 서버가 싫어한다. */
async function mapLimit(items, limit, run) {
  const results = new Array(items.length);
  let next = 0;

  async function worker() {
    while (next < items.length) {
      const i = next++;
      results[i] = await run(items[i], i);
    }
  }

  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return results;
}

// ── e-Class가 스스로 부르는 주소 기록 ─────────────────────────────────────
//
// LearningX 고유 경로는 학교·버전마다 달라서 찍어서 맞힐 수가 없다.
// 그래서 페이지가 실제로 주고받는 요청 중 "날짜처럼 생긴 값이 든 JSON"만 골라 적어둔다.
// 응답 복사본을 읽기만 하고 원래 흐름은 그대로 흘려보낸다. 기록은 이 탭의
// sessionStorage 에만 남고, 탭을 닫으면 사라진다.

const CAPTURE_KEY = 'gwaje.captured.v1';
const CAPTURE_MAX = 40;
const DATE_VALUE = /^\d{4}[-./]\d{1,2}[-./]\d{1,2}([T ]\d{1,2}:\d{2})?/;
const DATE_KEY = /(due|end|close|deadline|start|open|expire|limit)[_-]?(at|date|time|dt)?$/i;

function readCaptures() {
  try {
    return JSON.parse(sessionStorage.getItem(CAPTURE_KEY) || '[]');
  } catch {
    return [];
  }
}

function writeCaptures(list) {
  try {
    sessionStorage.setItem(CAPTURE_KEY, JSON.stringify(list.slice(0, CAPTURE_MAX)));
  } catch {
    // 용량이 차면 조용히 포기한다. 기록은 부가 기능이지 본체가 아니다.
  }
}

/** 날짜처럼 생긴 값을 가진 키를 훑어 모으고, 대표 항목 하나를 집어온다. */
function scanForDates(node, depth = 0, found = { fields: new Set(), sample: null, count: 0 }) {
  if (node == null || depth > 5) return found;

  if (Array.isArray(node)) {
    if (!found.sample && node.length && typeof node[0] === 'object') {
      found.sample = node[0];
      found.count = node.length;
    }
    for (const child of node.slice(0, 5)) scanForDates(child, depth + 1, found);
    return found;
  }

  if (typeof node !== 'object') return found;

  for (const [key, value] of Object.entries(node)) {
    if (typeof value === 'string' && value && (DATE_VALUE.test(value) || DATE_KEY.test(key))) {
      if (DATE_VALUE.test(value)) found.fields.add(key);
    }
    scanForDates(value, depth + 1, found);
  }
  return found;
}

function recordCapture(url, method, text) {
  if (!text || text.length > 3_000_000) return;

  let json;
  try {
    json = JSON.parse(text.replace(/^\s*while\s*\(1\);?/, ''));
  } catch {
    return;
  }

  const found = scanForDates(json);
  if (!found.fields.size) return; // 날짜가 없으면 마감일과 무관하다

  let path;
  try {
    const parsed = new URL(url, location.href);
    if (parsed.origin !== location.origin) return;
    path = parsed.pathname;
  } catch {
    return;
  }

  const list = readCaptures();
  const entry = {
    path,
    method: method || 'GET',
    fields: [...found.fields].slice(0, 10),
    keys: found.sample && typeof found.sample === 'object' ? Object.keys(found.sample).slice(0, 16) : [],
    title: titleOf(found.sample) || '',
    count: found.count,
  };

  writeCaptures([entry, ...list.filter((e) => e.path !== path)]);
}

function isSelfRequest(init, input) {
  const headers = init?.headers ?? (input && typeof input !== 'string' ? input.headers : null);
  if (!headers) return false;
  if (typeof headers.get === 'function') return Boolean(headers.get(SELF_HEADER));
  return Boolean(headers[SELF_HEADER]);
}

function installCapture() {
  if (window.__gwajeCaptureOn) return;
  window.__gwajeCaptureOn = true;

  const originalFetch = window.fetch;
  window.fetch = function (...args) {
    const promise = originalFetch.apply(this, args);
    try {
      const input = args[0];
      // 우리가 보낸 요청은 기록하지 않는다. 알고 싶은 건 e-Class가 스스로 부르는 주소다.
      if (!isSelfRequest(args[1], input)) {
        const url = typeof input === 'string' ? input : input?.url;
        const method = (args[1]?.method || input?.method || 'GET').toUpperCase();
        promise
          .then((res) => res.clone().text())
          .then((text) => recordCapture(url, method, text))
          .catch(() => {});
      }
    } catch {}
    return promise;
  };

  const open = XMLHttpRequest.prototype.open;
  const send = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (method, url, ...rest) {
    this.__gwaje = { method, url };
    return open.call(this, method, url, ...rest);
  };
  XMLHttpRequest.prototype.send = function (...args) {
    this.addEventListener('load', () => {
      try {
        if (this.responseType === '' || this.responseType === 'text') {
          recordCapture(this.__gwaje?.url, this.__gwaje?.method, this.responseText);
        }
      } catch {}
    });
    return send.apply(this, args);
  };
}

/** 경로 하나를 찔러보고 사람이 읽을 줄을 돌려준다. 절대 예외를 던지지 않는다. */
async function probeOne(origin, path) {
  let res;
  try {
    res = await withTimeout(PROBE_TIMEOUT_MS, (signal) =>
      getJSON(joinUrl(origin, path), { signal })
    );
  } catch (err) {
    if (err?.name === 'AbortError') return [`✗ ${path} — 응답 없음(시간 초과)`];
    if (err instanceof NotJsonError || err?.name === 'NotJsonError') {
      return [`✗ ${path} — JSON이 아님(없는 경로일 가능성)`];
    }
    if (err?.name === 'NotLoggedInError') return [`✗ ${path} — 로그인 페이지로 튕김`];
    // HttpError 메시지에는 URL이 통째로 들어있어 잘리면 읽기만 나빠진다. 상태 코드면 충분하다.
    if (err instanceof HttpError || err?.name === 'HttpError') {
      return [`✗ ${path} — HTTP ${err.status}${err.status === 404 ? ' (없는 경로)' : ''}`];
    }
    return [`✗ ${path} — ${String(err?.message || err).slice(0, 60)}`];
  }

  // 배열이 아니라 객체 하나만 돌려주는 엔드포인트(users/self 등)도 0개로 보이면 곤란하다.
  const found = flatten(extractArray(res.data));
  const list = found.length || !res.data || typeof res.data !== 'object' ? found : [res.data];

  const lines = [`✓ ${path} — ${list.length}개`];
  const keys = list.length && typeof list[0] === 'object' ? Object.keys(list[0]).slice(0, 14) : [];
  if (keys.length) lines.push(`    필드: ${keys.join(', ')}`);

  // 제목이 있어야 "여기에 강의영상이 있나"를 눈으로 판단할 수 있다.
  const titles = list.slice(0, 4).map(titleOf).filter(Boolean);
  if (titles.length) lines.push(`    예: ${titles.join(' | ')}`);

  return lines;
}

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

/** 과목마다 일정한 색. 확장 대시보드와 같은 방식. */
function courseColor(name) {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) % 360;
  return `hsl(${h} 62% 55%)`;
}

class Board {
  constructor() {
    this.items = [];
    this.view = 'task'; // 'task' = 해야 할 것, 'resource' = 공지·강의자료
    this.filters = { q: '', course: '', hideDone: false };

    const host = el('div');
    host.id = HOST_ID;
    this.host = host;
    this.root = host.attachShadow({ mode: 'open' });

    const style = document.createElement('style');
    style.textContent = CSS;

    this.backdrop = el('div', 'backdrop');
    this.sheet = el('div', 'sheet');
    this.backdrop.append(this.sheet);

    // 배경을 누르면 닫는다. 시트 안쪽 터치는 통과시키지 않는다.
    this.backdrop.addEventListener('click', (e) => {
      if (e.target === this.backdrop) this.close();
    });
    this.onKey = (e) => {
      if (e.key === 'Escape') this.close();
    };
    document.addEventListener('keydown', this.onKey);

    this.root.append(style, this.backdrop);
    document.documentElement.append(host);

    this.buildChrome();
  }

  buildChrome() {
    const header = el('header');
    const titleBox = el('div');
    titleBox.style.flex = '1';
    this.title = el('h1', null, '과제 한눈에');
    this.status = el('span', 'sub', '불러오는 중…');
    this.version = BUILD_REF; // 어느 버전이 도는지 진단 화면에서 확인용
    titleBox.append(this.title, this.status);

    const refresh = el('button', 'icon', '↻');
    refresh.title = '새로고침';
    refresh.addEventListener('click', () => this.load());

    const probe = el('button', 'icon', '⋯');
    probe.title = '진단';
    probe.addEventListener('click', () => this.runProbe());

    const close = el('button', 'icon', '✕');
    close.title = '닫기';
    close.addEventListener('click', () => this.close());

    header.append(titleBox, probe, refresh, close);

    this.body = el('main');
    this.sheet.append(header, this.body);
  }

  close() {
    document.removeEventListener('keydown', this.onKey);
    this.host.remove();
    delete window.__gwajeBoard;
  }

  showMessage(titleText, detail, action) {
    this.body.textContent = '';
    const box = el('div', 'msg');
    box.append(el('strong', null, titleText));
    box.append(document.createTextNode(detail));

    // 막힌 곳에서 끝내지 않고 다음 행동을 바로 누를 수 있게 한다.
    if (action) {
      const link = el('a', 'cta', action.label);
      link.href = action.href;
      box.append(link);
    }

    this.body.append(box);
  }

  showLoading(label = '과목별 과제를 모으는 중…') {
    this.body.textContent = '';
    const box = el('div', 'msg');
    box.append(el('div', 'spin'));
    box.append(el('div', null, label));
    this.body.append(box);
  }

  async load() {
    this.showLoading();
    this.status.textContent = '동기화 중…';

    try {
      // LearningX 어댑터는 Canvas 표준 API에 더해 주차별 강의영상 같은 자체 항목까지 긁는다.
      const raw = await withTimeout(LOAD_TIMEOUT_MS, (signal) =>
        learningxAdapter.fetchAll(location.origin, {
          signal,
          lookAheadDays: LOOK_AHEAD_DAYS,
          lookBackDays: LOOK_BACK_DAYS,
          settings: {},
        })
      );

      const cutoff = Date.now() - LOOK_BACK_DAYS * 86400000;
      this.items = sortByDue(
        dedupe(raw).filter((a) => !a.dueAt || new Date(a.dueAt).getTime() >= cutoff)
      );

      // 어느 학기를 보고 있는지 적어둔다. 과목이 안 보일 때 스스로 확인할 수 있어야 한다.
      const term = this.items.find((a) => a.term)?.term;
      const time = new Date().toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' });
      this.status.textContent = `${term ? `${term} · ` : ''}${time} 기준 · 총 ${this.items.length}개`;

      this.buildTools();
      this.render();
    } catch (err) {
      this.status.textContent = '가져오지 못했습니다';
      const message = String(err?.message || err);

      const openEclass = { label: 'e-Class 열기', href: ECLASS_URL };

      if (err?.name === 'NotLoggedInError' || /로그인/.test(message)) {
        this.showMessage(
          'e-Class 로그인이 필요합니다',
          '로그인한 다음 이 북마크를 다시 눌러주세요.',
          openEclass
        );
      } else if (!ON_ECLASS.test(location.hostname)) {
        // 사파리 시작 페이지처럼 주소가 없는 화면이면 hostname이 빈 문자열이다.
        this.showMessage(
          'e-Class 화면에서 눌러주세요',
          location.hostname
            ? `지금 보고 있는 ${location.hostname} 에서는 과제를 가져올 수 없습니다. 북마클릿은 열려 있는 페이지 안에서 실행되기 때문입니다.`
            : '지금은 e-Class가 아닌 화면입니다(사파리 시작 페이지 등). 북마클릿은 열려 있는 페이지 안에서 실행되기 때문에, e-Class 탭으로 옮긴 뒤 눌러야 합니다.',
          openEclass
        );
      } else if (err?.name === 'AbortError') {
        this.showMessage(
          '응답이 너무 늦습니다',
          'e-Class가 제때 답하지 않았습니다. 잠시 뒤 새로고침(↻)을 눌러보세요.'
        );
      } else {
        this.showMessage('과제를 가져오지 못했습니다', message);
      }
    }
  }

  buildTools() {
    if (this.seg) this.seg.remove();
    if (this.tools) this.tools.remove();
    if (this.stats) this.stats.remove();
    if (this.footer) this.footer.remove();

    // 과제와 자료를 한 목록에 섞으면 둘 다 안 보인다. 칸을 나눈다.
    this.seg = el('div', 'seg');
    this.segButtons = {};
    for (const [view, label] of [['task', '과제'], ['resource', '자료 · 공지']]) {
      const button = el('button', null, label);
      button.type = 'button';
      button.setAttribute('aria-selected', String(this.view === view));
      const count = el('span', 'c');
      button.append(count);
      this.segCounts = this.segCounts || {};
      this.segCounts[view] = count;

      button.addEventListener('click', () => {
        this.view = view;
        for (const [key, node] of Object.entries(this.segButtons)) {
          node.setAttribute('aria-selected', String(key === view));
        }
        this.render();
      });
      this.segButtons[view] = button;
      this.seg.append(button);
    }

    this.stats = el('div', 'stats');

    this.tools = el('div', 'tools');
    const search = document.createElement('input');
    search.type = 'search';
    search.placeholder = '과제 · 과목 검색';
    search.addEventListener('input', () => {
      this.filters.q = search.value.trim().toLowerCase();
      this.render();
    });

    const course = document.createElement('select');
    course.append(new Option('전체 과목', ''));
    for (const name of [...new Set(this.items.map((a) => a.courseName))].sort((a, b) => a.localeCompare(b, 'ko'))) {
      course.append(new Option(name, name));
    }
    course.addEventListener('change', () => {
      this.filters.course = course.value;
      this.render();
    });

    const toggle = el('label', 'toggle');
    const check = document.createElement('input');
    check.type = 'checkbox';
    check.addEventListener('change', () => {
      this.filters.hideDone = check.checked;
      this.render();
    });
    toggle.append(check, document.createTextNode('제출완료 숨기기'));
    this.submittedToggle = toggle;

    this.tools.append(search, course, toggle);

    this.footer = el('footer');
    const ics = el('button', 'primary', '캘린더에 넣기');
    ics.addEventListener('click', () => this.exportICS());
    this.footer.append(ics);

    this.sheet.insertBefore(this.seg, this.body);
    this.sheet.insertBefore(this.stats, this.body);
    this.sheet.insertBefore(this.tools, this.body);
    this.sheet.append(this.footer);
  }

  /** 현재 탭과 무관하게, 검색·과목 필터만 적용한 목록. */
  matching() {
    const { q, course, hideDone } = this.filters;
    return this.items.filter((a) => {
      if (hideDone && a.submitted) return false;
      if (course && a.courseName !== course) return false;
      if (q && !`${a.title} ${a.courseName}`.toLowerCase().includes(q)) return false;
      return true;
    });
  }

  visible() {
    return this.matching().filter((a) => a.kind === this.view);
  }

  /**
   * 어느 엔드포인트가 살아있는지 훑어본다.
   * LearningX 경로는 학교·버전마다 달라서, 영상이 안 잡힐 때 여기서 실제 이름을 확인한다.
   */
  async runProbe() {
    if (this.seg) this.seg.hidden = true;
    if (this.tools) this.tools.hidden = true;
    if (this.stats) this.stats.hidden = true;
    this.showLoading('엔드포인트를 훑는 중…');
    this.status.textContent = '엔드포인트 확인 중…';

    // 진단은 무슨 일이 있어도 결과를 보여주고 끝나야 한다.
    // 여기서 예외가 새면 스피너만 남고 아무것도 알 수 없게 된다.
    try {
      this.renderProbe(await this.collectProbe());
    } catch (err) {
      this.renderProbe(`진단 중 오류: ${String(err?.message || err)}`);
    }
  }

  async collectProbe() {
    const origin = location.origin;

    const courses =
      (await tryOr(
        () => withTimeout(PROBE_TIMEOUT_MS, (signal) => fetchCourses(origin, signal)),
        new Map()
      )) || new Map();
    const courseId = [...courses.keys()][0];

    const paths = [
      '/api/v1/users/self',
      '/api/v1/courses?enrollment_state=active&per_page=100',
      '/api/v1/planner/items?per_page=10',
      '/api/v1/users/self/todo',
      '/learningx/api/v1/users/self/todo_items',
      '/learningx/api/v1/users/self/todos',
      '/learningx/api/v1/dashboard/todo',
    ];
    if (courseId) {
      paths.push(
        `/api/v1/courses/${courseId}/assignments?per_page=5`,
        `/api/v1/courses/${courseId}/modules?include[]=items&per_page=5`,
        `/learningx/api/v1/courses/${courseId}/activities`,
        `/learningx/api/v1/courses/${courseId}/learning_activities`,
        `/learningx/api/v1/courses/${courseId}/modules/items`,
        `/learningx/api/v1/courses/${courseId}/course_modules`,
        `/learningx/api/v1/courses/${courseId}/attendance/items`,
        `/learningx/api/v1/courses/${courseId}/attendance_items`,
        `/learningx/api/v1/courses/${courseId}/progress`,
        `/learningx/api/v1/courses/${courseId}/progress/commons`,
        `/learningx/api/v1/courses/${courseId}/study_status`,
        `/learningx/api/v1/courses/${courseId}/commons`
      );
    }

    let done = 0;
    const results = await mapLimit(paths, 4, async (path) => {
      const line = await probeOne(origin, path);
      this.showLoading(`엔드포인트를 훑는 중… (${++done}/${paths.length})`);
      return line;
    });

    const captured = readCaptures();
    const capturedLines = captured.length
      ? [
          '',
          `── e-Class가 스스로 부른 주소 ${captured.length}개 ──`,
          ...captured.flatMap((e) => {
            const lines = [`· ${e.method} ${e.path}${e.count ? ` — ${e.count}개` : ''}`];
            lines.push(`    날짜 필드: ${e.fields.join(', ')}`);
            if (e.keys.length) lines.push(`    필드: ${e.keys.join(', ')}`);
            if (e.title) lines.push(`    예: ${e.title}`);
            return lines;
          }),
        ]
      : [
          '',
          '── e-Class가 스스로 부른 주소: 아직 없음 ──',
          '  이 창을 닫고 아무 과목의 "주차별 학습" 페이지를 연 뒤,',
          '  북마크를 다시 눌러 여기로 오면 기록이 쌓입니다.',
        ];

    return [
      `빌드 ${BUILD_REF} · 과목 ${courses.size}개 · 기준 과목 ${courseId || '없음'}`,
      '',
      ...results.flat(),
      ...capturedLines,
    ].join('\n');
  }

  renderProbe(text) {
    this.status.textContent = '진단 결과';
    this.body.textContent = '';

    const box = el('div', 'probe');
    const pre = el('pre', null, text);
    box.append(pre);

    const copy = el('button', 'primary', '결과 복사');
    copy.addEventListener('click', async () => {
      try {
        await navigator.clipboard.writeText(text);
        copy.textContent = '복사했습니다';
      } catch {
        copy.textContent = '복사 실패 — 위 내용을 길게 눌러 복사하세요';
      }
    });

    const back = el('button', null, '목록으로');
    back.addEventListener('click', () => {
      if (this.seg) this.seg.hidden = false;
      if (this.tools) this.tools.hidden = false;
      this.render();
    });

    const actions = el('div', 'probe-actions');
    actions.append(copy, back);
    box.append(actions);
    this.body.append(box);
  }

  render() {
    const now = Date.now();
    const matching = this.matching();

    // 탭 숫자는 현재 검색·과목 필터 기준으로 센다.
    if (this.segCounts) {
      this.segCounts.task.textContent = String(matching.filter((a) => a.kind === 'task').length);
      this.segCounts.resource.textContent = String(matching.filter((a) => a.kind === 'resource').length);
    }

    const list = this.visible();
    const isTask = this.view === 'task';

    // 자료에는 마감 통계도, 제출 여부도 의미가 없다.
    this.stats.hidden = !isTask;
    if (this.submittedToggle) this.submittedToggle.hidden = !isTask;
    if (isTask) this.renderStats(list, now);

    this.body.textContent = '';

    if (!list.length) {
      this.showMessage(
        isTask ? '해야 할 과제가 없습니다' : '올라온 자료가 없습니다',
        this.items.length ? '검색어나 과목 필터를 지워보세요.' : '가져온 항목이 없습니다.'
      );
      return;
    }

    if (isTask) this.renderTasks(sortByDue(list), now);
    else this.renderResources(sortByPosted(list), now);
  }

  renderTasks(list, now) {
    const grouped = new Map();
    for (const a of list) {
      const key = bucketOf(a, now);
      if (!grouped.has(key)) grouped.set(key, []);
      grouped.get(key).push(a);
    }

    for (const { key, label } of BUCKETS) {
      const group = grouped.get(key);
      if (!group?.length) continue;

      const head = el('div', 'glabel', label);
      head.append(el('span', 'n', String(group.length)));

      const card = el('div', 'card');
      for (const a of group) card.append(this.rowNode(a, now));

      this.body.append(head, card);
    }
  }

  /** 자료·공지는 마감이 없다. 최근에 올라온 것부터 보면 된다. */
  renderResources(list, now) {
    const head = el('div', 'glabel', '최근 올라온 순');
    head.append(el('span', 'n', String(list.length)));

    const card = el('div', 'card');
    for (const a of list) card.append(this.rowNode(a, now));

    this.body.append(head, card);
  }

  renderStats(list, now) {
    const open = (a) => a.kind === 'task' && !a.submitted && a.dueAt;
    const within = (a, n) => {
      const d = ddayInfo(a.dueAt, now).days;
      return d >= 0 && d <= n;
    };

    const cells = [
      ['지난 마감', list.filter((a) => open(a) && bucketOf(a, now) === 'overdue').length, 'overdue'],
      ['오늘', list.filter((a) => open(a) && bucketOf(a, now) === 'today').length, 'urgent'],
      ['3일 내', list.filter((a) => open(a) && within(a, 3)).length, 'soon'],
      ['7일 내', list.filter((a) => open(a) && within(a, 7)).length, ''],
    ];

    this.stats.textContent = '';
    for (const [label, n, cls] of cells) {
      const box = el('div', `stat ${cls}`);
      box.append(el('div', 'n', String(n)), el('div', 'k', label));
      this.stats.append(box);
    }
  }

  rowNode(a, now) {
    const node = a.url ? document.createElement('a') : el('div');
    node.className = `row${a.submitted ? ' done' : ''}`;
    if (a.url) {
      node.href = a.url;
      node.target = '_blank';
      node.rel = 'noopener';
    }

    if (a.kind === 'resource') {
      // 마감이 없는 항목에 D-day를 붙이면 거짓말이 된다.
      node.append(
        el('span', 'dday posted',
          a.postedAt
            ? new Date(a.postedAt).toLocaleDateString('ko-KR', { month: 'numeric', day: 'numeric' })
            : '—'
        )
      );
    } else {
      const d = ddayInfo(a.dueAt, now);
      node.append(el('span', `dday ${d.tone}`, d.label));
    }

    const mid = el('div');
    mid.append(el('div', 't', a.title));

    const meta = el('div', 'm');
    const dot = el('span', 'dot');
    dot.style.background = courseColor(a.courseName);
    meta.append(dot, el('span', null, a.courseName), el('span', 'tag', TYPE_LABEL[a.type] || a.type));

    const when = a.kind === 'resource' ? a.postedAt : a.dueAt;
    if (when) {
      const stamp = new Date(when).toLocaleString('ko-KR', {
        month: 'numeric', day: 'numeric', weekday: 'short', hour: '2-digit', minute: '2-digit',
      });
      meta.append(el('span', null, a.kind === 'resource' ? `${stamp} 올라옴` : stamp));
    }
    // 영상은 제출하는 게 아니라 보는 것이다.
    if (a.submitted) meta.append(el('span', 'tag ok', a.type === 'video' ? '시청완료' : '제출완료'));

    mid.append(meta);
    node.append(mid);
    return node;
  }

  exportICS() {
    // 자료 탭을 보고 있어도 캘린더에는 마감이 있는 과제만 넣는다.
    const list = this.matching().filter((a) => a.kind === 'task' && a.dueAt && !a.submitted);
    if (!list.length) {
      this.status.textContent = '캘린더에 넣을 과제가 없습니다';
      return;
    }

    const blob = new Blob([toICS(list)], { type: 'text/calendar;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `과제마감일_${new Date().toISOString().slice(0, 10)}.ics`;
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 5000);
  }
}

// 이미 떠 있으면 닫는다 (북마크를 토글처럼 쓸 수 있게).
// 보드를 열든 닫든 기록은 켜둔다. 한 번 켜두면 이후 페이지 이동에서도 계속 모인다.
installCapture();

if (window.__gwajeBoard) {
  window.__gwajeBoard.close();
} else {
  const board = new Board();
  window.__gwajeBoard = board;
  board.load();
}
