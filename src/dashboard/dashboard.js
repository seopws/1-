import { ddayInfo, bucketOf, BUCKETS, TYPE_LABEL, sortByDue } from '../lib/model.js';
import { downloadICS } from '../lib/ics.js';

const $ = (id) => document.getElementById(id);
let state = { assignments: [], lastSync: null, lastError: null, settings: null };

function send(type, extra = {}) {
  return chrome.runtime.sendMessage({ type, ...extra }).then((r) => {
    if (!r?.ok) throw new Error(r?.error || '요청 실패');
    return r.data;
  });
}

/** 과목별로 일정한 색을 준다. 이름 해시 → HSL 색상. */
function courseColor(name) {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) % 360;
  return `hsl(${h} 62% 55%)`;
}

async function load() {
  state = await send('getState');
  $('hideSubmitted').checked = Boolean(state.settings?.hideSubmitted);
  fillSelects();
  render();
}

function fillSelects() {
  const courses = [...new Set(state.assignments.map((a) => a.courseName))].sort((a, b) =>
    a.localeCompare(b, 'ko')
  );
  const types = [...new Set(state.assignments.map((a) => a.type))];

  const keepCourse = $('course').value;
  const keepType = $('type').value;

  $('course').innerHTML = '<option value="">전체 과목</option>';
  for (const c of courses) {
    const o = document.createElement('option');
    o.value = c;
    o.textContent = c;
    $('course').append(o);
  }
  $('course').value = courses.includes(keepCourse) ? keepCourse : '';

  $('type').innerHTML = '<option value="">전체 유형</option>';
  for (const t of types) {
    const o = document.createElement('option');
    o.value = t;
    o.textContent = TYPE_LABEL[t] || t;
    $('type').append(o);
  }
  $('type').value = types.includes(keepType) ? keepType : '';
}

function filtered() {
  const q = $('search').value.trim().toLowerCase();
  const course = $('course').value;
  const type = $('type').value;
  const hideSubmitted = $('hideSubmitted').checked;
  const hideNoDue = $('hideNoDue').checked;

  return sortByDue(
    state.assignments.filter((a) => {
      if (hideSubmitted && a.submitted) return false;
      if (hideNoDue && !a.dueAt) return false;
      if (course && a.courseName !== course) return false;
      if (type && a.type !== type) return false;
      if (q && !`${a.title} ${a.courseName}`.toLowerCase().includes(q)) return false;
      return true;
    })
  );
}

function render() {
  const now = Date.now();

  $('alert').hidden = !state.lastError;
  if (state.lastError) $('alert').textContent = state.lastError;

  $('status').textContent = state.lastSync
    ? `마지막 동기화 ${new Date(state.lastSync.at).toLocaleString('ko-KR')} · ${state.lastSync.adapter} 어댑터 · 총 ${state.assignments.length}개`
    : '아직 동기화하지 않았습니다. 오른쪽 위 새로고침을 눌러주세요.';

  const list = filtered();
  renderStats(list, now);

  const board = $('board');
  board.textContent = '';

  if (!list.length) {
    const d = document.createElement('div');
    d.className = 'empty';
    d.textContent = state.assignments.length
      ? '조건에 맞는 과제가 없습니다.'
      : '아직 불러온 과제가 없습니다. 설정에서 LMS 주소를 확인한 뒤 새로고침해 주세요.';
    board.append(d);
    return;
  }

  const grouped = new Map();
  for (const a of list) {
    const k = bucketOf(a, now);
    if (!grouped.has(k)) grouped.set(k, []);
    grouped.get(k).push(a);
  }

  for (const { key, label } of BUCKETS) {
    const items = grouped.get(key);
    if (!items?.length) continue;

    const section = document.createElement('section');
    section.className = 'group';

    const h2 = document.createElement('h2');
    h2.textContent = label;
    const n = document.createElement('span');
    n.className = 'n';
    n.textContent = items.length;
    h2.append(n);

    const card = document.createElement('div');
    card.className = 'card';
    for (const a of items) card.append(rowNode(a, now));

    section.append(h2, card);
    board.append(section);
  }
}

function renderStats(list, now) {
  const count = (fn) => list.filter(fn).length;
  const active = (a) => !a.submitted && a.dueAt;

  const stats = [
    { k: '지난 마감', n: count((a) => active(a) && bucketOf(a, now) === 'overdue'), cls: 'overdue' },
    { k: '오늘 마감', n: count((a) => active(a) && bucketOf(a, now) === 'today'), cls: 'urgent' },
    { k: '3일 내', n: count((a) => active(a) && ddayInfo(a.dueAt, now).days >= 0 && ddayInfo(a.dueAt, now).days <= 3), cls: 'soon' },
    { k: '7일 내', n: count((a) => active(a) && ddayInfo(a.dueAt, now).days >= 0 && ddayInfo(a.dueAt, now).days <= 7), cls: '' },
  ];

  const box = $('stats');
  box.textContent = '';
  for (const s of stats) {
    const d = document.createElement('div');
    d.className = `stat ${s.cls}`;
    const n = document.createElement('div');
    n.className = 'n';
    n.textContent = s.n;
    const k = document.createElement('div');
    k.className = 'k';
    k.textContent = s.k;
    d.append(n, k);
    box.append(d);
  }
}

function rowNode(a, now) {
  const d = ddayInfo(a.dueAt, now);
  const node = document.createElement(a.url ? 'a' : 'div');
  node.className = `row${a.submitted ? ' done' : ''}`;
  if (a.url) {
    node.href = a.url;
    node.target = '_blank';
    node.rel = 'noopener';
  }

  const badge = document.createElement('span');
  badge.className = `dday ${d.tone}`;
  badge.textContent = d.label;

  const mid = document.createElement('div');
  const title = document.createElement('div');
  title.className = 'title';
  title.textContent = a.title;

  const sub = document.createElement('div');
  sub.className = 'sub';

  const dot = document.createElement('span');
  dot.className = 'dot';
  dot.style.background = courseColor(a.courseName);

  const course = document.createElement('span');
  course.textContent = a.courseName;

  const tag = document.createElement('span');
  tag.className = 'tag';
  tag.textContent = TYPE_LABEL[a.type] || a.type;

  sub.append(dot, course, tag);
  if (a.submitted) {
    const done = document.createElement('span');
    done.className = 'tag done';
    done.textContent = '제출완료';
    sub.append(done);
  }

  mid.append(title, sub);

  const when = document.createElement('div');
  when.className = 'when';
  when.textContent = a.dueAt
    ? new Date(a.dueAt).toLocaleString('ko-KR', {
        month: 'long', day: 'numeric', weekday: 'short', hour: '2-digit', minute: '2-digit',
      })
    : '기한 없음';

  node.append(badge, mid, when);
  return node;
}

// ---------------------------------------------------------------- 이벤트

$('refresh').addEventListener('click', async (e) => {
  e.currentTarget.disabled = true;
  $('status').textContent = '동기화 중…';
  try {
    await send('sync');
  } catch (err) {
    state.lastError = err.message;
  } finally {
    e.currentTarget.disabled = false;
    await load();
  }
});

$('export').addEventListener('click', () => {
  const list = filtered().filter((a) => a.dueAt && !a.submitted);
  if (!list.length) {
    alert('내보낼 과제가 없습니다.');
    return;
  }
  downloadICS(list, `과제마감일_${new Date().toISOString().slice(0, 10)}.ics`);
});

$('options').addEventListener('click', () => chrome.runtime.openOptionsPage());

for (const id of ['search', 'course', 'type', 'hideNoDue']) {
  $(id).addEventListener('input', render);
}
$('hideSubmitted').addEventListener('change', async (e) => {
  await send('saveSettings', { patch: { hideSubmitted: e.target.checked } });
  render();
});

chrome.storage.onChanged.addListener((changes, area) => {
  if (area === 'local' && ('assignments' in changes || 'lastSync' in changes)) load();
});

load();
