import { ddayInfo, bucketOf, BUCKETS, TYPE_LABEL } from '../lib/model.js';

const el = {
  status: document.getElementById('status'),
  alert: document.getElementById('alert'),
  list: document.getElementById('list'),
  count: document.getElementById('count'),
  tabs: document.getElementById('tabs'),
};

let state = { assignments: [], lastSync: null, lastError: null, settings: null };
let scope = 'soon';

function send(type, extra = {}) {
  return chrome.runtime.sendMessage({ type, ...extra }).then((r) => {
    if (!r?.ok) throw new Error(r?.error || '요청 실패');
    return r.data;
  });
}

async function load() {
  state = await send('getState');
  render();
}

function visible() {
  const now = Date.now();
  // 팝업은 좁다. 공지·강의자료는 빼고 해야 할 것만 보여준다. (전체는 대시보드에서)
  let list = state.assignments.filter((a) => a.kind === 'task');

  if (state.settings?.hideSubmitted) list = list.filter((a) => !a.submitted);

  if (scope === 'today') {
    list = list.filter((a) => ['today', 'tomorrow', 'overdue'].includes(bucketOf(a, now)));
  } else if (scope === 'soon') {
    list = list.filter((a) => {
      const b = bucketOf(a, now);
      return b !== 'later' && b !== 'nodue';
    });
  }
  return list;
}

function render() {
  const now = Date.now();

  el.alert.hidden = !state.lastError;
  if (state.lastError) el.alert.textContent = state.lastError;

  const term = state.assignments.find((a) => a.term)?.term;
  el.status.textContent = state.lastSync
    ? `${term ? `${term} · ` : ''}${new Date(state.lastSync.at).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })} 기준`
    : '아직 동기화 전';

  const list = visible();
  el.count.textContent = `${list.length}개`;
  el.list.textContent = '';

  if (!list.length) {
    const div = document.createElement('div');
    div.className = 'empty';
    div.textContent = state.settings?.origin
      ? '표시할 과제가 없습니다. ↻ 를 눌러 새로고침해 보세요.'
      : '먼저 설정에서 학교 LMS 주소를 입력해 주세요.';
    el.list.append(div);
    return;
  }

  const grouped = new Map();
  for (const a of list) {
    const key = bucketOf(a, now);
    if (!grouped.has(key)) grouped.set(key, []);
    grouped.get(key).push(a);
  }

  for (const { key, label } of BUCKETS) {
    const items = grouped.get(key);
    if (!items?.length) continue;

    const head = document.createElement('div');
    head.className = 'group-label';
    head.textContent = `${label} · ${items.length}`;
    el.list.append(head);

    for (const a of items) el.list.append(itemNode(a, now));
  }
}

function itemNode(a, now) {
  const d = ddayInfo(a.dueAt, now);

  const node = document.createElement(a.url ? 'a' : 'div');
  node.className = `item${a.submitted ? ' done' : ''}`;
  if (a.url) {
    node.href = a.url;
    node.target = '_blank';
    node.rel = 'noopener';
  }

  const badge = document.createElement('span');
  badge.className = `dday ${d.tone}`;
  badge.textContent = d.label;

  const body = document.createElement('div');
  body.className = 'body';

  const title = document.createElement('div');
  title.className = 'title';
  title.textContent = a.title;
  title.title = a.title;

  const meta = document.createElement('div');
  meta.className = 'meta';
  const due = a.dueAt
    ? new Date(a.dueAt).toLocaleString('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
    : '기한 없음';
  const doneLabel = a.type === 'video' ? ' · 시청완료' : ' · 제출완료';
  meta.textContent = `${a.courseName} · ${TYPE_LABEL[a.type]} · ${due}${a.submitted ? doneLabel : ''}`;

  body.append(title, meta);
  node.append(badge, body);
  return node;
}

document.getElementById('refresh').addEventListener('click', async (e) => {
  const btn = e.currentTarget;
  btn.disabled = true;
  el.status.textContent = '동기화 중…';
  try {
    await send('sync');
  } catch (err) {
    state.lastError = err.message;
  } finally {
    btn.disabled = false;
    await load();
  }
});

document.getElementById('open').addEventListener('click', () => send('openDashboard'));
document.getElementById('options').addEventListener('click', () => chrome.runtime.openOptionsPage());

el.tabs.addEventListener('click', (e) => {
  const btn = e.target.closest('button[data-scope]');
  if (!btn) return;
  scope = btn.dataset.scope;
  [...el.tabs.children].forEach((b) => b.classList.toggle('active', b === btn));
  render();
});

load();
