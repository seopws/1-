// Canvas LMS 표준 API 어댑터.
// LearningX는 Canvas LMS 위에 올라간 제품이라, 대부분의 학교에서 /api/v1/* 가 그대로 살아있다.
// 여러 엔드포인트를 동시에 찔러보고 되는 것만 모아 쓴다 — 학교마다 막아둔 게 다르기 때문.

import { getJSON, getAllPages, tryOr, joinUrl } from '../lib/http.js';
import { normalize } from '../lib/model.js';

const SOURCE = 'canvas';

/**
 * @param fallback 엔드포인트가 이미 종류를 보장할 때 쓰는 기본값.
 *   /courses/:id/assignments 로 받은 항목은 필드가 뭐든 과제다. 추측할 필요가 없다.
 */
function typeFromCanvas(item, fallback = 'other') {
  const t = String(item.plannable_type || item.type || '').toLowerCase();

  // 공지·페이지·일정을 과제와 같은 칸에 넣으면 목록이 못 쓰게 된다. 먼저 걸러낸다.
  if (t.includes('announcement')) return 'notice';
  if (t.includes('wiki') || t.includes('page')) return 'material';
  if (t.includes('calendar_event') || t === 'event') return 'event';

  if (t.includes('quiz')) return 'quiz';
  if (t.includes('discussion')) return 'discussion';
  if (t.includes('assignment')) return 'assignment';
  if (t.includes('attendance')) return 'attendance';
  if (t.includes('media') || t.includes('video')) return 'video';

  // submission_types 는 assignment 안에서만 의미가 있다.
  const submission = String(item.submission_types?.[0] || '').toLowerCase();
  if (submission.includes('quiz')) return 'quiz';
  if (submission.includes('discussion')) return 'discussion';
  if (submission) return 'assignment';

  return fallback;
}

/** 수강 중인 과목 목록. id → 이름 매핑을 만든다. */
export async function fetchCourses(origin, signal) {
  const courses =
    (await tryOr(() =>
      getAllPages(
        joinUrl(origin, '/api/v1/courses?enrollment_state=active&per_page=100&include[]=term'),
        { signal }
      )
    )) || [];

  const map = new Map();
  for (const c of courses) {
    if (!c || c.access_restricted_by_date) continue;
    map.set(String(c.id), c.name || c.course_code || `과목 ${c.id}`);
  }
  return map;
}

/** planner/items: Canvas가 "할 일"을 이미 합쳐서 주는 가장 좋은 소스. */
async function fromPlanner(origin, courseNames, { signal, lookBackDays, lookAheadDays }) {
  const start = new Date(Date.now() - lookBackDays * 86400000).toISOString();
  const end = new Date(Date.now() + lookAheadDays * 86400000).toISOString();
  const url = joinUrl(
    origin,
    `/api/v1/planner/items?start_date=${start}&end_date=${end}&per_page=100`
  );

  const items = await getAllPages(url, { signal });
  return items
    .filter((it) => it && it.plannable)
    .map((it) => {
      const p = it.plannable;
      return normalize(
        {
          id: `planner:${it.plannable_type}:${p.id}`,
          source: SOURCE,
          courseId: it.course_id,
          courseName: courseNames.get(String(it.course_id)) || it.context_name,
          title: p.title || p.name,
          type: typeFromCanvas(it),
          // plannable_date 로 폴백하면 안 된다. 공지에서는 그게 "올라온 날짜"라서,
          // 지난주에 올라온 강의자료가 전부 "마감 지남"으로 둔갑한다.
          dueAt: p.due_at || p.todo_date || null,
          postedAt: p.posted_at || p.created_at || it.plannable_date || null,
          url: it.html_url ? joinUrl(origin, it.html_url) : '',
          submitted: it.submissions ? Boolean(it.submissions.submitted) : null,
          points: p.points_possible ?? null,
        },
        { source: SOURCE }
      );
    });
}

/** users/self/todo: planner가 막혀 있을 때의 대안. 미제출 과제만 나온다. */
async function fromTodo(origin, courseNames, { signal }) {
  const { data } = await getJSON(joinUrl(origin, '/api/v1/users/self/todo?per_page=100'), { signal });
  const items = Array.isArray(data) ? data : [];
  return items.map((it) => {
    const a = it.assignment || {};
    return normalize(
      {
        id: `todo:${a.id ?? it.html_url}`,
        source: SOURCE,
        courseId: it.course_id ?? a.course_id,
        courseName: courseNames.get(String(it.course_id ?? a.course_id)) || it.context_name,
        title: a.name || it.type,
        type: typeFromCanvas(a, 'assignment'),
        dueAt: a.due_at,
        postedAt: a.created_at || null,
        url: it.html_url ? joinUrl(origin, it.html_url) : '',
        submitted: false,
        points: a.points_possible ?? null,
      },
      { source: SOURCE }
    );
  });
}

/** 과목별 assignments: 가장 확실하지만 과목 수만큼 요청이 나간다. */
async function fromCourseAssignments(origin, courseNames, { signal }) {
  const ids = [...courseNames.keys()];
  const results = await Promise.all(
    ids.map((id) =>
      tryOr(() =>
        getAllPages(
          joinUrl(
            origin,
            `/api/v1/courses/${id}/assignments?per_page=100&include[]=submission&order_by=due_at`
          ),
          { signal, maxPages: 3 }
        )
      , [])
    )
  );

  const out = [];
  results.forEach((list, i) => {
    const courseId = ids[i];
    for (const a of list || []) {
      if (!a || !a.id) continue;
      out.push(
        normalize(
          {
            id: `assignment:${a.id}`,
            source: SOURCE,
            courseId,
            courseName: courseNames.get(courseId),
            title: a.name,
            type: typeFromCanvas(a, 'assignment'),
            dueAt: a.due_at,
            postedAt: a.created_at || null,
            startAt: a.unlock_at,
            url: a.html_url ? joinUrl(origin, a.html_url) : '',
            submitted: a.submission
              ? Boolean(a.submission.submitted_at || a.submission.workflow_state === 'graded')
              : null,
            points: a.points_possible ?? null,
          },
          { source: SOURCE }
        )
      );
    }
  });
  return out;
}

// 제목만으로 영상을 알아보는 규칙. LearningX는 영상을 LTI(ExternalTool)로 끼워 넣는 일이 많아
// 타입만으로는 구분이 안 된다.
const VIDEO_TITLE = /영상|동영상|시청|녹화|강의보기|vod|video|lecture|streaming/i;

function typeFromModuleItem(item) {
  const type = String(item.type || '').toLowerCase();
  const title = String(item.title || '');

  if (type === 'externaltool' || VIDEO_TITLE.test(title)) return 'video';
  if (type === 'page' || type === 'file' || type === 'attachment' || type === 'externalurl') {
    return 'material';
  }
  return 'other';
}

/**
 * 주차별 학습활동(모듈)에서 "봐야 하는 것"을 가져온다.
 *
 * LearningX의 강의영상은 대개 여기 모듈 항목으로 들어있다. 마감일은 없지만
 * completion_requirement 가 "이걸 봐야 한다"와 "봤는지"를 알려준다.
 *
 * 과제·퀴즈·토론 항목은 일부러 건너뛴다. 그쪽은 assignments/planner 에서
 * 제대로 된 마감일과 함께 이미 가져오므로, 여기서 또 담으면 마감일 없는
 * 중복이 생긴다.
 */
async function fromModules(origin, courseNames, { signal }) {
  const ids = [...courseNames.keys()];

  const perCourse = await Promise.all(
    ids.map(async (courseId) => {
      const modules =
        (await tryOr(() =>
          getAllPages(
            joinUrl(origin, `/api/v1/courses/${courseId}/modules?include[]=items&per_page=50`),
            { signal, maxPages: 3 }
          )
        , [])) || [];

      const out = [];
      for (const mod of modules) {
        if (!mod) continue;

        // Canvas는 항목이 많으면 items 를 빼고 준다. 그럴 땐 따로 받아온다.
        let items = mod.items;
        if (!Array.isArray(items)) {
          items =
            (await tryOr(() =>
              getAllPages(
                joinUrl(origin, `/api/v1/courses/${courseId}/modules/${mod.id}/items?per_page=50`),
                { signal, maxPages: 2 }
              )
            , [])) || [];
        }

        for (const item of items) {
          if (!item || !item.title) continue;

          const type = String(item.type || '').toLowerCase();
          if (type === 'subheader') continue;
          if (['assignment', 'quiz', 'discussion'].includes(type)) continue;

          const requirement = item.completion_requirement;
          const kind = typeFromModuleItem(item);

          // 아무 요구사항도 없고 영상처럼 보이지도 않으면 그냥 자료 더미다. 안 담는다.
          if (!requirement && kind !== 'video') continue;

          out.push(
            normalize(
              {
                id: `module:${item.id}`,
                source: SOURCE,
                courseId,
                courseName: courseNames.get(courseId),
                title: item.title,
                type: kind,
                dueAt: null, // 모듈 항목에는 마감일이 없다
                url: item.html_url ? joinUrl(origin, item.html_url) : '',
                submitted: requirement ? Boolean(requirement.completed) : null,
                note: mod.name || '',
              },
              { source: SOURCE }
            )
          );
        }
      }
      return out;
    })
  );

  return perCourse.flat();
}

export const canvasAdapter = {
  id: 'canvas',
  label: 'Canvas 표준 API (/api/v1)',

  /** 이 학교 LMS에 Canvas API가 살아있는지 가볍게 확인. */
  async detect(origin, signal) {
    const probe = await tryOr(() =>
      getJSON(joinUrl(origin, '/api/v1/users/self'), { signal })
    );
    return Boolean(probe && probe.data && (probe.data.id || probe.data.name));
  },

  async fetchAll(origin, opts) {
    const courseNames = await fetchCourses(origin, opts.signal);

    // 세 소스를 병렬로 긁고, 되는 것만 합친다. dedupe는 호출부에서.
    const [planner, todo, byCourse, modules] = await Promise.all([
      tryOr(() => fromPlanner(origin, courseNames, opts), []),
      tryOr(() => fromTodo(origin, courseNames, opts), []),
      courseNames.size ? tryOr(() => fromCourseAssignments(origin, courseNames, opts), []) : [],
      courseNames.size ? tryOr(() => fromModules(origin, courseNames, opts), []) : [],
    ]);

    const all = [
      ...(planner || []), ...(todo || []), ...(byCourse || []), ...(modules || []),
    ];
    if (!all.length) {
      throw new Error(
        'Canvas API는 응답했지만 과제를 하나도 못 찾았습니다. 옵션에서 다른 어댑터를 시도해 보세요.'
      );
    }
    return all;
  },
};
