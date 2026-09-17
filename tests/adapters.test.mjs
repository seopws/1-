// 어댑터를 가짜 LMS 응답에 물려서 end-to-end로 돌려본다.
import test from 'node:test';
import assert from 'node:assert/strict';

import { canvasAdapter } from '../src/adapters/canvas.js';
import { customAdapter } from '../src/adapters/custom.js';
import { NotLoggedInError } from '../src/lib/http.js';
import { dedupe, sortByDue } from '../src/lib/model.js';

const ORIGIN = 'https://lms.test.ac.kr';

/** 경로 → 응답 본문 맵으로 가짜 fetch를 만든다. */
function stubFetch(routes) {
  const calls = [];
  global.fetch = async (url) => {
    calls.push(url);
    const path = new URL(url).pathname + new URL(url).search;
    // 가장 길게 일치하는 경로를 고른다. (/api/v1/courses 가 /api/v1/courses/101/... 를 가로채지 않도록)
    const key = Object.keys(routes)
      .filter((k) => path.startsWith(k))
      .sort((a, b) => b.length - a.length)[0];

    if (!key) return new Response('not found', { status: 404 });

    const value = routes[key];
    if (value === 'LOGIN_HTML') {
      return new Response('<!doctype html><html>로그인</html>', {
        status: 200,
        headers: { 'content-type': 'text/html' },
      });
    }
    return new Response(JSON.stringify(value), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    });
  };
  return calls;
}

test('canvas 어댑터: planner / todo / 과목별 과제를 합쳐서 정규화한다', async () => {
  stubFetch({
    '/api/v1/users/self': { id: 1, name: '학생' },
    '/api/v1/courses': [
      { id: 101, name: '통계학개론' },
      { id: 102, name: '운영체제' },
    ],
    '/api/v1/planner/items': [
      {
        course_id: 101,
        plannable_type: 'assignment',
        html_url: '/courses/101/assignments/1',
        submissions: { submitted: false },
        plannable: { id: 1, title: '1주차 레포트', due_at: '2026-03-14T14:59:00Z', points_possible: 10 },
      },
    ],
    '/api/v1/users/self/todo': [
      {
        course_id: 102,
        html_url: '/courses/102/quizzes/9',
        assignment: { id: 9, name: '중간 퀴즈', due_at: '2026-04-01T05:00:00Z', submission_types: ['online_quiz'] },
      },
    ],
    '/api/v1/courses/101/assignments': [
      { id: 1, name: '1주차 레포트', due_at: '2026-03-14T14:59:00Z', html_url: '/courses/101/assignments/1', submission: { submitted_at: null } },
    ],
    '/api/v1/courses/102/assignments': [],
  });

  assert.equal(await canvasAdapter.detect(ORIGIN), true);

  const raw = await canvasAdapter.fetchAll(ORIGIN, { lookAheadDays: 60, lookBackDays: 14 });
  const items = sortByDue(dedupe(raw));

  assert.equal(items.length, 2, '중복된 1주차 레포트는 하나로 합쳐져야 한다');

  const [report, quiz] = items;
  assert.equal(report.title, '1주차 레포트');
  assert.equal(report.courseName, '통계학개론');
  assert.equal(report.type, 'assignment');
  assert.equal(report.submitted, false);
  assert.equal(report.url, 'https://lms.test.ac.kr/courses/101/assignments/1');

  assert.equal(quiz.title, '중간 퀴즈');
  assert.equal(quiz.courseName, '운영체제');
  assert.equal(quiz.type, 'quiz');
});

test('canvas 어댑터: 공지와 강의자료를 과제와 구분한다', async () => {
  stubFetch({
    '/api/v1/users/self': { id: 1, name: '학생' },
    '/api/v1/courses': [{ id: 201, name: '논리회로 01분반' }],
    '/api/v1/planner/items': [
      {
        // 실제로 걸렸던 함정: 공지에는 due_at이 없고 plannable_date는 "올라온 날짜"다.
        course_id: 201,
        plannable_type: 'announcement',
        html_url: '/courses/201/discussion_topics/11',
        plannable_date: '2026-09-07T05:54:00Z',
        plannable: { id: 11, title: '1_digital_systems_part3', posted_at: '2026-09-07T05:54:00Z' },
      },
      {
        course_id: 201,
        plannable_type: 'wiki_page',
        html_url: '/courses/201/pages/syllabus',
        plannable_date: '2026-09-02T01:00:00Z',
        plannable: { id: 12, title: '강의계획서' },
      },
      {
        course_id: 201,
        plannable_type: 'assignment',
        html_url: '/courses/201/assignments/13',
        submissions: { submitted: false },
        plannable: { id: 13, title: '3주차 과제', due_at: '2026-09-20T14:59:00Z' },
      },
    ],
    '/api/v1/users/self/todo': [],
    '/api/v1/courses/201/assignments': [],
  });

  const items = dedupe(await canvasAdapter.fetchAll(ORIGIN, { lookAheadDays: 60, lookBackDays: 14 }));
  const byTitle = Object.fromEntries(items.map((a) => [a.title, a]));

  const notice = byTitle['1_digital_systems_part3'];
  assert.equal(notice.type, 'notice');
  assert.equal(notice.kind, 'resource');
  assert.equal(notice.dueAt, null, '올라온 날짜가 마감일로 새어들면 안 된다');
  assert.equal(notice.postedAt, '2026-09-07T05:54:00.000Z');

  assert.equal(byTitle['강의계획서'].type, 'material');
  assert.equal(byTitle['강의계획서'].kind, 'resource');

  const task = byTitle['3주차 과제'];
  assert.equal(task.kind, 'task');
  assert.equal(task.dueAt, '2026-09-20T14:59:00.000Z');
});

test('canvas 어댑터: Canvas API가 없는 사이트는 detect가 false', async () => {
  stubFetch({});
  assert.equal(await canvasAdapter.detect(ORIGIN), false);
});

test('JSON 자리에 로그인 HTML이 오면 NotLoggedInError로 올린다', async () => {
  stubFetch({ '/api/v1/users/self/todo': 'LOGIN_HTML' });

  const { getJSON } = await import('../src/lib/http.js');
  await assert.rejects(
    () => getJSON(`${ORIGIN}/api/v1/users/self/todo`),
    (err) => err instanceof NotLoggedInError
  );
});

test('custom 어댑터: 과목 목록을 돌며 {courseId}를 치환한다', async () => {
  const calls = stubFetch({
    '/my/courses': { result: { list: [{ cid: 'A1', cname: '자료구조' }, { cid: 'B2', cname: '선형대수' }] } },
    '/my/courses/A1/works': { result: { items: [{ seq: 7, subject: '과제 1', limitDt: '2026-05-02 23:59:00', done: 'N', link: '/work/7' }] } },
    '/my/courses/B2/works': { result: { items: [] } },
  });

  const settings = {
    custom: {
      coursesUrl: '/my/courses',
      coursesPath: 'result.list',
      courseMap: { id: 'cid', name: 'cname' },
      itemsUrl: '/my/courses/{courseId}/works',
      itemsPath: 'result.items',
      map: { id: 'seq', title: 'subject', dueAt: 'limitDt', url: 'link', submitted: 'done', courseName: '', courseId: '', type: '' },
    },
  };

  const items = await customAdapter.fetchAll(ORIGIN, { settings });

  assert.equal(items.length, 1);
  assert.equal(items[0].title, '과제 1');
  assert.equal(items[0].courseName, '자료구조');
  assert.equal(items[0].url, 'https://lms.test.ac.kr/work/7');
  assert.equal(items[0].submitted, false, "'N'은 미제출로 읽어야 한다");
  assert.match(items[0].dueAt, /^2026-05-0[23]T/);

  assert.ok(calls.some((u) => u.includes('/my/courses/A1/works')));
  assert.ok(calls.some((u) => u.includes('/my/courses/B2/works')));
});

test('custom 어댑터: 설정이 비어 있으면 무엇을 채워야 하는지 알려준다', async () => {
  stubFetch({});
  await assert.rejects(
    () => customAdapter.fetchAll(ORIGIN, { settings: { custom: { itemsUrl: '', map: {} } } }),
    /과제 목록 URL/
  );
});
