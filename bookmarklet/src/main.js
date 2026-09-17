// 아이패드/아이폰용 북마클릿.
//
// iOS에는 크롬 확장이 없다. 대신 e-Class 페이지 안에서 직접 실행되는 스크립트를 쓴다.
// 페이지 컨텍스트에서 돌기 때문에 로그인 세션을 그대로 물려받고(동일 출처),
// 확장과 똑같이 학번/비밀번호를 알 필요가 없다.
//
// 어댑터·날짜 계산·ics 생성은 확장과 같은 모듈을 그대로 쓴다. 화면만 새로 그린다.

import { canvasAdapter } from '../../src/adapters/canvas.js';
import { dedupe, sortByDue, ddayInfo, bucketOf, BUCKETS, TYPE_LABEL } from '../../src/lib/model.js';
import { toICS } from '../../src/lib/ics.js';

const HOST_ID = 'gwaje-hannune-root';
const ECLASS_URL = 'https://eclass3.cau.ac.kr/';
// 서브도메인 경계를 지켜서 검사한다. /cau\.ac\.kr$/ 만 쓰면 evilcau.ac.kr 도 통과한다.
const ON_ECLASS = /(^|\.)cau\.ac\.kr$/;
const LOOK_AHEAD_DAYS = 60;
const LOOK_BACK_DAYS = 14;

const CSS = `
:host { all: initial; }
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

footer { display: flex; gap: 8px; padding: 11px 16px calc(11px + env(safe-area-inset-bottom)); border-top: 1px solid #e4e7ec; background: #fff; }
footer button { flex: 1; }

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
  .msg strong { color: #e7eaee; }
  .msg .cta { background: #a4bcfd; color: #14161a; }
  .dday.overdue { color: #fda29b; background: #3a1c19; }
  .dday.urgent  { color: #fdb894; background: #3a2318; }
  .dday.soon    { color: #fdd87d; background: #382e14; }
  .dday.week    { color: #7cd4fd; background: #12283a; }
  .dday.later, .dday.none { color: #cfd4dc; background: #262a30; }
  .tag.ok { background: #12283a; color: #7cd4fd; }
}
`;

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
    titleBox.append(this.title, this.status);

    const refresh = el('button', 'icon', '↻');
    refresh.title = '새로고침';
    refresh.addEventListener('click', () => this.load());

    const close = el('button', 'icon', '✕');
    close.title = '닫기';
    close.addEventListener('click', () => this.close());

    header.append(titleBox, refresh, close);

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

  showLoading() {
    this.body.textContent = '';
    const box = el('div', 'msg');
    box.append(el('div', 'spin'));
    box.append(el('div', null, '과목별 과제를 모으는 중…'));
    this.body.append(box);
  }

  async load() {
    this.showLoading();
    this.status.textContent = '동기화 중…';

    try {
      const raw = await canvasAdapter.fetchAll(location.origin, {
        lookAheadDays: LOOK_AHEAD_DAYS,
        lookBackDays: LOOK_BACK_DAYS,
        settings: {},
      });

      const cutoff = Date.now() - LOOK_BACK_DAYS * 86400000;
      this.items = sortByDue(
        dedupe(raw).filter((a) => !a.dueAt || new Date(a.dueAt).getTime() >= cutoff)
      );

      this.status.textContent = `${new Date().toLocaleTimeString('ko-KR', {
        hour: '2-digit', minute: '2-digit',
      })} 기준 · 총 ${this.items.length}개`;

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
      } else {
        this.showMessage('과제를 가져오지 못했습니다', message);
      }
    }
  }

  buildTools() {
    if (this.tools) this.tools.remove();
    if (this.stats) this.stats.remove();
    if (this.footer) this.footer.remove();

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

    this.tools.append(search, course, toggle);

    this.footer = el('footer');
    const ics = el('button', 'primary', '캘린더에 넣기 (.ics)');
    ics.addEventListener('click', () => this.exportICS());
    this.footer.append(ics);

    this.sheet.insertBefore(this.stats, this.body);
    this.sheet.insertBefore(this.tools, this.body);
    this.sheet.append(this.footer);
  }

  visible() {
    const { q, course, hideDone } = this.filters;
    return this.items.filter((a) => {
      if (hideDone && a.submitted) return false;
      if (course && a.courseName !== course) return false;
      if (q && !`${a.title} ${a.courseName}`.toLowerCase().includes(q)) return false;
      return true;
    });
  }

  render() {
    const now = Date.now();
    const list = this.visible();

    this.renderStats(list, now);
    this.body.textContent = '';

    if (!list.length) {
      this.showMessage('표시할 과제가 없습니다', this.items.length ? '검색어나 필터를 지워보세요.' : '마감일이 걸린 과제가 없습니다.');
      return;
    }

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

  renderStats(list, now) {
    const open = (a) => !a.submitted && a.dueAt;
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
    const d = ddayInfo(a.dueAt, now);
    const node = a.url ? document.createElement('a') : el('div');
    node.className = `row${a.submitted ? ' done' : ''}`;
    if (a.url) {
      node.href = a.url;
      node.target = '_blank';
      node.rel = 'noopener';
    }

    node.append(el('span', `dday ${d.tone}`, d.label));

    const mid = el('div');
    mid.append(el('div', 't', a.title));

    const meta = el('div', 'm');
    const dot = el('span', 'dot');
    dot.style.background = courseColor(a.courseName);
    meta.append(dot, el('span', null, a.courseName), el('span', 'tag', TYPE_LABEL[a.type] || a.type));

    if (a.dueAt) {
      meta.append(
        el('span', null,
          new Date(a.dueAt).toLocaleString('ko-KR', {
            month: 'numeric', day: 'numeric', weekday: 'short', hour: '2-digit', minute: '2-digit',
          })
        )
      );
    }
    if (a.submitted) meta.append(el('span', 'tag ok', '제출완료'));

    mid.append(meta);
    node.append(mid);
    return node;
  }

  exportICS() {
    const list = this.visible().filter((a) => a.dueAt && !a.submitted);
    if (!list.length) {
      this.status.textContent = '내보낼 과제가 없습니다';
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
if (window.__gwajeBoard) {
  window.__gwajeBoard.close();
} else {
  const board = new Board();
  window.__gwajeBoard = board;
  board.load();
}
