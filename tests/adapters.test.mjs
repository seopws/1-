// 어댑터를 가짜 LMS 응답에 물려서 end-to-end로 돌려본다.
import test from 'node:test';
import assert from 'node:assert/strict';

import { canvasAdapter } from '../src/adapters/canvas.js';
import { customAdapter } from '../src/adapters/custom.js';
import { NotLoggedInError } from '../src/lib/http.js';
import { dedupe, sortByDue } from '../src/lib/model.js';

const ORIGIN = 'https://lms.test.ac.kr';

/** Response.url 과 redirected 는 읽기 전용 게터라 defineProperty 로 심어야 한다. */
function htmlResponse({ url, redirected }) {
  const res = new Response('<!doctype html><html>…</html>', {
    status: 200,
    headers: { 'content-type': 'text/html' },
  });
  Object.defineProperty(res, 'url', { value: url });
  Object.defineProperty(res, 'redirected', { value: redirected });
  return res;
}

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
    if (value === 'LOGIN_HTML' || value === 'NOT_FOUND_HTML') {
      return htmlResponse(
        value === 'LOGIN_HTML'
          ? { url: `${ORIGIN}/login/canvas`, redirected: true }
          : { url: `${ORIGIN}${path}`, redirected: false }
      );
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

test('로그인 페이지로 튕기면 NotLoggedInError로 올린다', async () => {
  stubFetch({ '/api/v1/users/self/todo': 'LOGIN_HTML' });

  const { getJSON } = await import('../src/lib/http.js');
  await assert.rejects(
    () => getJSON(`${ORIGIN}/api/v1/users/self/todo`),
    (err) => err instanceof NotLoggedInError
  );
});

test('없는 경로의 HTML은 로그인 문제로 취급하지 않는다', async () => {
  // 이걸 구분 못 하면, 후보 경로를 찔러보다 HTML 하나만 만나도
  // 전체가 "로그인이 필요합니다"로 죽는다. 실제로 진단 화면이 그렇게 멈췄다.
  stubFetch({ '/learningx/api/v1/courses/1/activities': 'NOT_FOUND_HTML' });

  const { getJSON, NotJsonError, tryOr } = await import('../src/lib/http.js');
  await assert.rejects(
    () => getJSON(`${ORIGIN}/learningx/api/v1/courses/1/activities`),
    (err) => err instanceof NotJsonError && !(err instanceof NotLoggedInError)
  );

  // tryOr 는 로그인 문제만 다시 던지고 나머지는 삼켜야 한다.
  const fallback = await tryOr(
    () => getJSON(`${ORIGIN}/learningx/api/v1/courses/1/activities`),
    'skipped'
  );
  assert.equal(fallback, 'skipped');
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

test('learningx 어댑터: 감싸인 응답에서도 강의영상을 찾아낸다', async () => {
  const { learningxAdapter } = await import('../src/adapters/learningx.js');

  stubFetch({
    '/api/v1/users/self': { id: 1, name: '학생' },
    '/api/v1/courses': [{ id: 101, name: '운영체제' }],
    '/api/v1/planner/items': [],
    '/api/v1/users/self/todo': [],
    '/api/v1/courses/101/assignments': [
      { id: 1, name: '3주차 보고서', due_at: '2026-09-20T14:59:00Z', html_url: '/courses/101/assignments/1' },
    ],
    // 한국 LMS가 흔히 쓰는 {result:{items:[…]}} 래핑. 예전엔 여기서 배열을 못 찾았다.
    '/learningx/api/v1/courses/101/activities': {
      result: {
        items: [
          { id: 301, component_type: 'vod', title: '3주차 강의영상 1', due_at: '2026-09-19T14:59:00Z', progress_rate: 0 },
          { id: 302, component_type: 'vod', title: '3주차 강의영상 2', due_at: null, progress_rate: 100 },
        ],
      },
    },
  });

  const items = dedupe(await learningxAdapter.fetchAll(ORIGIN, { lookAheadDays: 60, lookBackDays: 14, settings: {} }));
  const videos = items.filter((a) => a.type === 'video');

  assert.equal(videos.length, 2, '감싸인 응답에서도 영상 두 개를 다 찾아야 한다');
  assert.ok(videos.every((v) => v.kind === 'task'), '들어야 할 영상은 할 일이다');

  const watched = videos.find((v) => v.title === '3주차 강의영상 2');
  assert.equal(watched.submitted, true, '진행률 100%는 시청 완료로 읽어야 한다');
  assert.equal(watched.dueAt, null);
  assert.ok(items.some((a) => a.title === '3주차 보고서'), 'Canvas 쪽 과제도 그대로 유지되어야 한다');
});

test('canvas 어댑터: 주차별 모듈에서 강의영상을 가져온다', async () => {
  const calls = stubFetch({
    '/api/v1/users/self': { id: 1, name: '학생' },
    '/api/v1/courses': [{ id: 101, name: '운영체제' }],
    '/api/v1/planner/items': [],
    '/api/v1/users/self/todo': [],
    '/api/v1/courses/101/assignments': [
      { id: 9, name: '3주차 과제', due_at: '2026-09-20T14:59:00Z', html_url: '/courses/101/assignments/9' },
    ],
    '/api/v1/courses/101/modules/77/items': [
      // items 가 인라인으로 안 왔을 때의 보조 경로
      { id: 205, type: 'ExternalTool', title: '4주차 강의영상', html_url: '/courses/101/modules/items/205',
        completion_requirement: { type: 'must_view', completed: false } },
    ],
    '/api/v1/courses/101/modules': [
      {
        id: 76,
        name: '3주차',
        items: [
          { id: 200, type: 'SubHeader', title: '3주차 학습' },
          { id: 201, type: 'ExternalTool', title: '3주차 강의영상 1', html_url: '/courses/101/modules/items/201',
            completion_requirement: { type: 'must_view', completed: true } },
          { id: 202, type: 'ExternalTool', title: '3주차 강의영상 2', html_url: '/courses/101/modules/items/202',
            completion_requirement: { type: 'must_view', completed: false } },
          { id: 203, type: 'Page', title: '3주차 강의노트', html_url: '/courses/101/modules/items/203',
            completion_requirement: { type: 'must_view', completed: false } },
          { id: 204, type: 'Page', title: '참고 링크 모음', html_url: '/courses/101/modules/items/204' },
          // 과제는 assignments 쪽에서 마감일과 함께 가져오므로 여기서는 건너뛰어야 한다
          { id: 9, type: 'Assignment', title: '3주차 과제', html_url: '/courses/101/assignments/9' },
        ],
      },
      { id: 77, name: '4주차' }, // items 없음 → 별도 요청으로 받아와야 한다
    ],
  });

  const items = dedupe(await canvasAdapter.fetchAll(ORIGIN, { lookAheadDays: 60, lookBackDays: 14 }));
  const byTitle = Object.fromEntries(items.map((a) => [a.title, a]));

  const video = byTitle['3주차 강의영상 1'];
  assert.equal(video.type, 'video');
  assert.equal(video.kind, 'task', '들어야 할 영상은 할 일이다');
  assert.equal(video.submitted, true, 'must_view 완료는 시청 완료로 읽어야 한다');
  assert.equal(byTitle['3주차 강의영상 2'].submitted, false);

  assert.equal(byTitle['3주차 강의노트'].type, 'material');
  assert.equal(byTitle['3주차 강의노트'].kind, 'resource');

  assert.equal(byTitle['참고 링크 모음'], undefined, '요구사항 없는 자료는 노이즈라 담지 않는다');
  assert.equal(byTitle['3주차 학습'], undefined, 'SubHeader 는 항목이 아니다');

  // 과제는 모듈이 아니라 assignments 쪽 것 하나만 남고, 마감일이 살아있어야 한다.
  assert.equal(items.filter((a) => a.title === '3주차 과제').length, 1);
  assert.equal(byTitle['3주차 과제'].dueAt, '2026-09-20T14:59:00.000Z');

  // items 가 빠진 모듈은 별도 요청으로 채운다.
  assert.ok(calls.some((u) => u.includes('/modules/77/items')));
  assert.equal(byTitle['4주차 강의영상'].type, 'video');
});

test('pickCurrentTerm: 진행 중인 학기를 고르고, 애매하면 거르지 않는다', async () => {
  const { pickCurrentTerm } = await import('../src/adapters/canvas.js');
  const now = new Date('2026-09-17T00:00:00Z').getTime();

  const course = (termId, name, start, end) => ({
    id: termId * 10,
    enrollment_term_id: termId,
    term: { id: termId, name, start_at: start, end_at: end },
  });

  // 지난 학기가 같이 딸려와도 지금 진행 중인 학기를 고른다.
  const picked = pickCurrentTerm(
    [
      course(11, '2026-1학기', '2026-03-02T00:00:00Z', '2026-06-30T00:00:00Z'),
      course(12, '2026-2학기', '2026-09-01T00:00:00Z', '2026-12-31T00:00:00Z'),
    ],
    now
  );
  assert.equal(picked.name, '2026-2학기');

  // 학기 날짜가 없으면 id가 큰 쪽. Canvas 학기 id는 증가한다.
  const noDates = pickCurrentTerm(
    [course(11, '지난 학기', null, null), course(12, '이번 학기', null, null)],
    now
  );
  assert.equal(noDates.name, '이번 학기');

  // 학기가 하나뿐이면 거를 이유가 없다.
  assert.equal(pickCurrentTerm([course(12, '2026-2학기', null, null)], now), null);
  assert.equal(pickCurrentTerm([], now), null);
});

test('fetchCourseIndex: 지난 학기 과목을 빼고 학기 이름을 알려준다', async () => {
  const { fetchCourseIndex } = await import('../src/adapters/canvas.js');

  stubFetch({
    '/api/v1/courses': [
      { id: 1, name: '지난학기 미적분', enrollment_term_id: 11,
        term: { id: 11, name: '2026-1학기', start_at: '2026-03-02T00:00:00Z', end_at: '2026-06-30T00:00:00Z' } },
      { id: 2, name: '운영체제', enrollment_term_id: 12,
        term: { id: 12, name: '2026-2학기', start_at: '2026-09-01T00:00:00Z', end_at: '2026-12-31T00:00:00Z' } },
      { id: 3, name: '논리회로', enrollment_term_id: 12,
        term: { id: 12, name: '2026-2학기', start_at: '2026-09-01T00:00:00Z', end_at: '2026-12-31T00:00:00Z' } },
    ],
  });

  const index = await fetchCourseIndex(ORIGIN);
  assert.deepEqual([...index.names.values()].sort(), ['논리회로', '운영체제']);
  assert.equal(index.termName, '2026-2학기');
  assert.equal(index.names.has('1'), false, '지난 학기 과목은 빠져야 한다');
});

test('fetchCourseIndex: 학기 정보가 없으면 아무것도 거르지 않는다', async () => {
  const { fetchCourseIndex } = await import('../src/adapters/canvas.js');

  stubFetch({
    '/api/v1/courses': [
      { id: 1, name: '과목 A' },
      { id: 2, name: '과목 B' },
    ],
  });

  const index = await fetchCourseIndex(ORIGIN);
  assert.equal(index.names.size, 2, '못 가르겠으면 숨기지 말아야 한다');
  assert.equal(index.termName, '');
});

test('모듈 항목: 요구사항으로 영상과 과제를 가른다', async () => {
  stubFetch({
    '/api/v1/users/self': { id: 1, name: '학생' },
    '/api/v1/courses': [{ id: 101, name: '운영체제' }],
    '/api/v1/planner/items': [],
    '/api/v1/users/self/todo': [],
    '/api/v1/courses/101/assignments': [],
    '/api/v1/courses/101/modules': [
      {
        id: 76,
        name: '3주차',
        items: [
          // LTI로 제출받는 과제. ExternalTool 이라고 영상 취급하면 안 된다.
          { id: 301, type: 'ExternalTool', title: '3주차 실습 제출', html_url: '/x/301',
            completion_requirement: { type: 'must_submit', completed: false } },
          // 봐야 하는 LTI = 영상
          { id: 302, type: 'ExternalTool', title: '3주차 학습하기', html_url: '/x/302',
            completion_requirement: { type: 'must_view', completed: false } },
          // 제목이 분명하면 제목을 믿는다
          { id: 303, type: 'ExternalTool', title: '3주차 강의자료', html_url: '/x/303',
            completion_requirement: { type: 'must_view', completed: false } },
          { id: 304, type: 'Page', title: '2주차 동영상 보충', html_url: '/x/304',
            completion_requirement: { type: 'must_view', completed: true } },
        ],
      },
    ],
  });

  const items = dedupe(await canvasAdapter.fetchAll(ORIGIN, { lookAheadDays: 60, lookBackDays: 14 }));
  const byTitle = Object.fromEntries(items.map((a) => [a.title, a]));

  assert.equal(byTitle['3주차 실습 제출'].type, 'assignment', 'must_submit 은 과제다');
  assert.equal(byTitle['3주차 학습하기'].type, 'video', '봐야 하는 LTI는 영상으로 본다');
  assert.equal(byTitle['3주차 강의자료'].type, 'material', '제목이 분명하면 제목이 이긴다');
  assert.equal(byTitle['2주차 동영상 보충'].type, 'video');
  assert.equal(byTitle['2주차 동영상 보충'].submitted, true);
});
