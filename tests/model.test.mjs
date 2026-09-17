import test from 'node:test';
import assert from 'node:assert/strict';

import { parseDue, normalize, dedupe, ddayInfo, bucketOf, sortByDue } from '../src/lib/model.js';
import { pick, parseNextLink } from '../src/lib/http.js';
import { toICS } from '../src/lib/ics.js';
import { cleanDueText } from '../src/adapters/html.js';

test('parseDue: 타임존이 붙은 ISO는 그대로 해석한다', () => {
  assert.equal(parseDue('2026-03-14T23:59:00Z'), '2026-03-14T23:59:00.000Z');
  assert.equal(parseDue('2026-03-14T23:59:00+09:00'), '2026-03-14T14:59:00.000Z');
});

test('parseDue: 타임존 없는 한국식 표기는 로컬 시각으로 본다', () => {
  const iso = parseDue('2026-03-14 23:59:59');
  const d = new Date(iso);
  assert.equal(d.getFullYear(), 2026);
  assert.equal(d.getMonth(), 2);
  assert.equal(d.getDate(), 14);
  assert.equal(d.getHours(), 23);
  assert.equal(d.getMinutes(), 59);
});

test('parseDue: 날짜만 있으면 그날 23:59로 본다', () => {
  const d = new Date(parseDue('2026.03.14'));
  assert.equal(d.getHours(), 23);
  assert.equal(d.getMinutes(), 59);
});

test('parseDue: epoch(초/밀리초)와 빈 값 처리', () => {
  assert.equal(parseDue(1773532800000), new Date(1773532800000).toISOString());
  assert.equal(parseDue(1773532800), new Date(1773532800000).toISOString());
  assert.equal(parseDue(null), null);
  assert.equal(parseDue(''), null);
  assert.equal(parseDue('이번 주까지'), null);
});

test('normalize: 빠진 필드를 안전한 기본값으로 채운다', () => {
  const a = normalize({ title: ' 보고서 제출 ', due_at: '2026-03-14T00:00:00Z' }, { source: 'x' });
  assert.equal(a.title, '보고서 제출');
  assert.equal(a.courseName, '(과목 미상)');
  assert.equal(a.type, 'other');
  assert.equal(a.submitted, null);
  assert.equal(a.source, 'x');
});

test('dedupe: 같은 과제는 정보가 많은 쪽을 남긴다', () => {
  const thin = normalize({ id: 1, title: '퀴즈 1', dueAt: '2026-03-14T00:00:00Z', courseName: '통계' });
  const rich = normalize({
    id: 2, title: '퀴즈 1', dueAt: '2026-03-14T00:00:00Z', courseName: '통계',
    url: 'https://lms/x', submitted: false, type: 'quiz',
  });

  const out = dedupe([thin, rich]);
  assert.equal(out.length, 1);
  assert.equal(out[0].url, 'https://lms/x');
  assert.equal(out[0].type, 'quiz');
});

test('ddayInfo / bucketOf: 자정 경계 기준으로 D-day를 센다', () => {
  const now = new Date('2026-03-14T10:00:00').getTime();
  const tomorrowMorning = new Date('2026-03-15T09:00:00').toISOString();

  assert.equal(ddayInfo(tomorrowMorning, now).label, 'D-1');
  assert.equal(bucketOf({ dueAt: tomorrowMorning }, now), 'tomorrow');

  const past = new Date('2026-03-14T08:00:00').toISOString();
  assert.equal(ddayInfo(past, now).tone, 'overdue');
  assert.equal(bucketOf({ dueAt: past }, now), 'overdue');

  assert.equal(bucketOf({ dueAt: null }, now), 'nodue');
  assert.equal(ddayInfo(null).label, '기한 없음');
});

test('sortByDue: 기한 없는 항목은 항상 뒤로 간다', () => {
  const list = [
    normalize({ title: 'C', dueAt: null, courseName: 'z' }),
    normalize({ title: 'B', dueAt: '2026-03-20T00:00:00Z' }),
    normalize({ title: 'A', dueAt: '2026-03-14T00:00:00Z' }),
  ];
  assert.deepEqual(sortByDue(list).map((a) => a.title), ['A', 'B', 'C']);
});

test('pick: 점 표기법과 배열 인덱스를 지원한다', () => {
  const obj = { data: { items: [{ due_at: '2026-01-01' }] } };
  assert.equal(pick(obj, 'data.items[0].due_at'), '2026-01-01');
  assert.equal(pick(obj, 'data.none.deep'), undefined);
  assert.equal(pick(obj, ''), undefined);
});

test('parseNextLink: Link 헤더에서 next만 골라낸다', () => {
  const header = '<https://lms/api?page=1>; rel="current",<https://lms/api?page=2>; rel="next"';
  assert.equal(parseNextLink(header), 'https://lms/api?page=2');
  assert.equal(parseNextLink(''), null);
});

test('cleanDueText: 문장에 섞인 날짜만 뽑아낸다', () => {
  assert.equal(cleanDueText('마감: 2026-03-14 23:59 까지'), '2026-03-14 23:59');
  assert.equal(cleanDueText('상시'), null);
});

test('toICS: 기한 있는 과제만 VEVENT로 나가고 알람이 붙는다', () => {
  const ics = toICS([
    normalize({ id: 'a1', title: '레포트', dueAt: '2026-03-14T14:59:00Z', courseName: '통계학' }),
    normalize({ id: 'a2', title: '기한없음', dueAt: null, courseName: '통계학' }),
  ]);

  assert.equal((ics.match(/BEGIN:VEVENT/g) || []).length, 1);
  assert.match(ics, /SUMMARY:\[통계학\] 레포트/);
  assert.match(ics, /TRIGGER:-P1D/);
  assert.match(ics, /DTEND:20260314T145900Z/);
  // 모든 줄이 CRLF로 끝나고 75옥텟 제한을 넘지 않아야 한다.
  assert.ok(ics.split('\r\n').every((line) => line.length <= 75));
});

test('기본 설정: 중앙대 e-Class로 바로 쓸 수 있게 고정되어 있다', async () => {
  const { DEFAULT_SETTINGS } = await import('../src/lib/storage.js');
  const manifest = JSON.parse(await (await import('node:fs/promises')).readFile('manifest.json', 'utf8'));

  assert.equal(DEFAULT_SETTINGS.origin, 'https://eclass3.cau.ac.kr');
  // 기본 주소가 manifest의 host_permissions에 없으면 설치 직후 조용히 실패한다.
  assert.ok(
    manifest.host_permissions.some((p) => p.startsWith(`${DEFAULT_SETTINGS.origin}/`)),
    '기본 origin은 manifest host_permissions에 선언되어 있어야 한다'
  );
});

test('공지·강의자료는 올라온 날짜를 마감일로 쓰지 않는다', async () => {
  const { kindOf } = await import('../src/lib/model.js');

  const notice = normalize({
    title: '반장 선임.', type: 'notice', courseName: '미분적분학(2)',
    dueAt: null, postedAt: '2026-09-11T02:14:00Z',
  });
  assert.equal(notice.dueAt, null, '공지에 마감일이 생기면 안 된다');
  assert.equal(notice.postedAt, '2026-09-11T02:14:00.000Z');
  assert.equal(notice.kind, 'resource');

  const material = normalize({ title: '1_digital_systems_part3', type: 'material', postedAt: '2026-09-07T05:54:00Z' });
  assert.equal(material.kind, 'resource');

  // 마감이 실제로 걸린 자료라면 할 일로 본다.
  assert.equal(kindOf({ type: 'material', dueAt: '2026-09-20T14:59:00Z' }), 'task');
  // 마감일이 없는 과제도 여전히 할 일이다.
  assert.equal(kindOf({ type: 'assignment', dueAt: null }), 'task');
});

test('sortByPosted: 최근에 올라온 것부터', async () => {
  const { sortByPosted } = await import('../src/lib/model.js');
  const list = [
    normalize({ title: '오래된 것', type: 'notice', postedAt: '2026-09-01T00:00:00Z' }),
    normalize({ title: '날짜 없음', type: 'notice' }),
    normalize({ title: '최근 것', type: 'notice', postedAt: '2026-09-15T00:00:00Z' }),
  ];
  assert.deepEqual(sortByPosted(list).map((a) => a.title), ['최근 것', '오래된 것', '날짜 없음']);
});

test('HTML 응답: 로그인 페이지와 없는 경로를 구분한다', async () => {
  const { getJSON, NotLoggedInError, NotJsonError } = await import('../src/lib/http.js');

  const html = (url, redirected) => {
    const res = new Response('<!doctype html><html>…</html>', {
      status: 200,
      headers: { 'content-type': 'text/html' },
    });
    Object.defineProperty(res, 'url', { value: url });
    Object.defineProperty(res, 'redirected', { value: redirected });
    return res;
  };

  // 로그인으로 튕긴 경우 — 세션 문제로 올려야 한다.
  global.fetch = async () => html('https://eclass3.cau.ac.kr/login/canvas', true);
  await assert.rejects(
    () => getJSON('https://eclass3.cau.ac.kr/api/v1/users/self'),
    (err) => err instanceof NotLoggedInError
  );

  // 없는 경로에 SPA 껍데기를 준 경우 — 이건 로그인 문제가 아니다.
  global.fetch = async () => html('https://eclass3.cau.ac.kr/learningx/api/v1/nope', false);
  await assert.rejects(
    () => getJSON('https://eclass3.cau.ac.kr/learningx/api/v1/nope'),
    (err) => err instanceof NotJsonError && !(err instanceof NotLoggedInError)
  );
});
