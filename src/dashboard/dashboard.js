import { ddayInfo, bucketOf, BUCKETS, TYPE_LABEL, sortByDue, sortByPosted } from '../lib/model.js';
import { downloadICS } from '../lib/ics.js';

const $ = (id) => document.getElementById(id);
let state = { assignments: [], lastSync: null, lastError: null, settings: null };
let view = 'task'; // 'task' = 해야 할 것, 'resource' = 공지·강의자료

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

/** 탭과 무관하게 검색·과목·유형 필터만 적용한 목록. 탭 숫자를 세는 데도 쓴다. */
function matching() {
  const q = $('search').value.trim().toLowerCase();
  const course = $('course').value;
  const type = $('type').value;
  const hideSubmitted = $('hideSubmitted').checked;
  const hideNoDue = $('hideNoDue').checked;

  return state.assignments.filter((a) => {
    if (hideSubmitted && a.submitted) return false;
    if (hideNoDue && a.kind === 'task' && !a.dueAt) return false;
    if (course && a.courseName !== course) return false;
    if (type && a.type !== type) return false;
    if (q && !`${a.title} ${a.courseName}`.toLowerCase().includes(q)) return false;
    return true;
  });
}

function filtered() {
  const list = matching().filter((a) => a.kind === view);
  return view === 'task' ? sortByDue(list) : sortByPosted(list);
}

function render() {
  const now = Date.now();

  $('alert').hidden = !state.lastError;
  if (state.lastError) $('alert').textContent = state.lastError;

  $('status').textContent = state.lastSync
    ? `마지막 동기화 ${new Date(state.lastSync.at).toLocaleString('ko-KR')} · ${state.lastSync.adapter} 어댑터 · 총 ${state.assignments.length}개`
    : '아직 동기화하지 않았습니다. 오른쪽 위 새로고침을 눌러주세요.';

  const pool = matching();
  for (const btn of $('seg').querySelectorAll('button')) {
    btn.setAttribute('aria-selected', String(btn.dataset.view === view));
    btn.querySelector('.c').textContent = pool.filter((a) => a.kind === btn.dataset.view).length;
  }

  const list = filtered();
  const isTask = view === 'task';

  // 자료에는 마감 통계도, 제출/기한 필터도 의미가 없다.
  $('stats').hidden = !isTask;
  for (const id of ['hideSubmitted', 'hideNoDue']) $(id).closest('.check').hidden = !isTask;
  if (isTask) renderStats(list, now);

  const board = $('board');
  board.textContent = '';

  if (!list.length) {
    const d = document.createElement('div');
    d.className = 'empty';
    d.textContent = state.assignments.length
      ? '조건에 맞는 항목이 없습니다.'
      : '아직 불러온 항목이 없습니다. 설정에서 LMS 주소를 확인한 뒤 새로고침해 주세요.';
    board.append(d);
    return;
  }

  // 마감이 있는 것만 마감 구간으로 묶는다. 자료는 최근 올라온 순 한 덩어리.
  const groups = isTask
    ? BUCKETS.map(({ key, label }) => [label, list.filter((a) => bucketOf(a, now) === key)])
    : [['최근 올라온 순', list]];

  for (const [label, items] of groups) {
    if (!items.length) continue;

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
  const active = (a) => a.kind === 'task' && !a.submitted && a.dueAt;

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
  const node = document.createElement(a.url ? 'a' : 'div');
  node.className = `row${a.submitted ? ' done' : ''}`;
  if (a.url) {
    node.href = a.url;
    node.target = '_blank';
    node.rel = 'noopener';
  }

  // 마감이 없는 자료에 D-day를 붙이면 거짓말이 된다. 올라온 날짜를 보여준다.
  const badge = document.createElement('span');
  if (a.kind === 'resource') {
    badge.className = 'dday posted';
    badge.textContent = a.postedAt
      ? new Date(a.postedAt).toLocaleDateString('ko-KR', { month: 'numeric', day: 'numeric' })
      : '—';
  } else {
    const d = ddayInfo(a.dueAt, now);
    badge.className = `dday ${d.tone}`;
    badge.textContent = d.label;
  }

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
    // 영상은 제출하는 게 아니라 보는 것이다.
    done.textContent = a.type === 'video' ? '시청완료' : '제출완료';
    sub.append(done);
  }

  mid.append(title, sub);

  const when = document.createElement('div');
  when.className = 'when';
  const stamp = a.kind === 'resource' ? a.postedAt : a.dueAt;
  when.textContent = stamp
    ? new Date(stamp).toLocaleString('ko-KR', {
        month: 'long', day: 'numeric', weekday: 'short', hour: '2-digit', minute: '2-digit',
      }) + (a.kind === 'resource' ? ' 올라옴' : '')
    : a.kind === 'resource' ? '날짜 모름' : '기한 없음';

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
  // 자료 탭을 보고 있어도 캘린더에는 마감이 있는 과제만 넣는다.
  const list = matching().filter((a) => a.kind === 'task' && a.dueAt && !a.submitted);
  if (!list.length) {
    alert('캘린더에 넣을 과제가 없습니다.');
    return;
  }
  downloadICS(list, `과제마감일_${new Date().toISOString().slice(0, 10)}.ics`);
});

$('options').addEventListener('click', () => chrome.runtime.openOptionsPage());

for (const id of ['search', 'course', 'type', 'hideNoDue']) {
  $(id).addEventListener('input', render);
}

$('seg').addEventListener('click', (e) => {
  const button = e.target.closest('button[data-view]');
  if (!button) return;
  view = button.dataset.view;
  render();
});
$('hideSubmitted').addEventListener('change', async (e) => {
  await send('saveSettings', { patch: { hideSubmitted: e.target.checked } });
  render();
});

chrome.storage.onChanged.addListener((changes, area) => {
  if (area === 'local' && ('assignments' in changes || 'lastSync' in changes)) load();
});

load();
