// Canvas LMS 표준 API 어댑터.
// LearningX는 Canvas LMS 위에 올라간 제품이라, 대부분의 학교에서 /api/v1/* 가 그대로 살아있다.
// 여러 엔드포인트를 동시에 찔러보고 되는 것만 모아 쓴다 — 학교마다 막아둔 게 다르기 때문.

import { getJSON, getAllPages, tryOr, joinUrl } from '../lib/http.js';
import { normalize } from '../lib/model.js';

const SOURCE = 'canvas';

function typeFromCanvas(item) {
  const t = String(
    item.plannable_type || item.type || item.submission_types?.[0] || ''
  ).toLowerCase();
  if (t.includes('quiz')) return 'quiz';
  if (t.includes('discussion')) return 'discussion';
  if (t.includes('assignment')) return 'assignment';
  if (t.includes('attendance')) return 'attendance';
  if (t.includes('media') || t.includes('video')) return 'video';
  return 'other';
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
          dueAt: p.due_at || p.todo_date || it.plannable_date,
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
        type: typeFromCanvas(a),
        dueAt: a.due_at,
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
            type: typeFromCanvas(a),
            dueAt: a.due_at,
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
    const [planner, todo, byCourse] = await Promise.all([
      tryOr(() => fromPlanner(origin, courseNames, opts), []),
      tryOr(() => fromTodo(origin, courseNames, opts), []),
      courseNames.size ? tryOr(() => fromCourseAssignments(origin, courseNames, opts), []) : [],
    ]);

    const all = [...(planner || []), ...(todo || []), ...(byCourse || [])];
    if (!all.length) {
      throw new Error(
        'Canvas API는 응답했지만 과제를 하나도 못 찾았습니다. 옵션에서 다른 어댑터를 시도해 보세요.'
      );
    }
    return all;
  },
};
