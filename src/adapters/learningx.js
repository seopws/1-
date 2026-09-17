// LearningX 전용 어댑터.
//
// LearningX는 Canvas 위에 자체 API(/learningx/api/v1/*)를 얹어서, Canvas의 assignments에는
// 안 잡히는 항목(주차별 학습활동, 동영상 시청, 출석 등)을 따로 관리하는 경우가 많다.
// 다만 이 경로는 학교/버전마다 다를 수 있어 "되면 좋고 아니면 넘어가는" 보조 소스로 쓴다.
// 실제 경로 확인법은 docs/DISCOVERY.md 참고.

import { getJSON, tryOr, joinUrl } from '../lib/http.js';
import { normalize } from '../lib/model.js';
import { canvasAdapter, fetchCourses } from './canvas.js';

const SOURCE = 'learningx';

// 마감일이 없어도 목록에 남길 만한 종류. 나머지 무명 항목은 노이즈라 버린다.
const KEEP_WITHOUT_DUE = new Set(['video', 'assignment', 'quiz', 'exam', 'discussion', 'attendance']);

// 학교마다 다를 수 있어 후보를 여러 개 두고 순서대로 시도한다.
const TODO_CANDIDATES = [
  '/learningx/api/v1/users/self/todo_items',
  '/learningx/api/v1/users/self/todos',
  '/learningx/api/v1/dashboard/todo',
];

const COURSE_ACTIVITY_CANDIDATES = [
  '/learningx/api/v1/courses/{courseId}/activities',
  '/learningx/api/v1/courses/{courseId}/learning_activities',
  '/learningx/api/v1/courses/{courseId}/modules/items',
  // 주차별 강의영상은 보통 이쪽 계열에 있다. 학교/버전마다 이름이 다르다.
  '/learningx/api/v1/courses/{courseId}/course_modules',
  '/learningx/api/v1/courses/{courseId}/attendance/items',
  '/learningx/api/v1/courses/{courseId}/progress/commons',
];

function typeFromLearningX(item) {
  const t = String(item.component_type || item.activity_type || item.type || '').toLowerCase();
  if (t.includes('notice') || t.includes('announce')) return 'notice';
  if (t.includes('material') || t.includes('file') || t.includes('attach')) return 'material';
  if (t.includes('quiz') || t.includes('exam')) return t.includes('exam') ? 'exam' : 'quiz';
  if (t.includes('assign') || t.includes('homework')) return 'assignment';
  if (t.includes('discussion') || t.includes('board')) return 'discussion';
  if (
    t.includes('video') || t.includes('movie') || t.includes('commons') ||
    t.includes('vod') || t.includes('media') || t.includes('lecture')
  ) {
    return 'video';
  }
  if (t.includes('attend')) return 'attendance';
  return 'other';
}

const ARRAY_KEYS = ['items', 'data', 'results', 'list', 'todo_items', 'activities', 'modules'];

/**
 * 응답 모양이 제각각이라 배열처럼 생긴 걸 찾아서 꺼낸다.
 *
 * {result:{items:[…]}} 처럼 한 겹 더 감싸는 API가 흔해서 재귀로 내려간다.
 * 예전에는 한 단계만 봤고, 그 탓에 응답은 200인데 목록이 비는 일이 있었다.
 */
function extractArray(data, depth = 0) {
  if (Array.isArray(data)) return data;
  if (!data || typeof data !== 'object' || depth > 3) return [];

  for (const key of ARRAY_KEYS) {
    if (Array.isArray(data[key])) return data[key];
  }

  for (const value of Object.values(data)) {
    if (Array.isArray(value) && value.length && typeof value[0] === 'object') return value;
  }

  // 아직 못 찾았으면 한 겹 더 들어간다.
  for (const value of Object.values(data)) {
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      const found = extractArray(value, depth + 1);
      if (found.length) return found;
    }
  }

  return [];
}

/** 완료 여부. 영상은 시청 완료/진행률로 오는 경우가 많다. */
function readDone(raw) {
  for (const key of ['is_submitted', 'submitted', 'is_completed', 'completed', 'is_viewed']) {
    if (typeof raw[key] === 'boolean') return raw[key];
  }
  const progress = raw.progress ?? raw.progress_rate ?? raw.view_rate;
  if (typeof progress === 'number') return progress >= 100;
  return null;
}

function toAssignment(raw, origin, courseNames) {
  const courseId = raw.course_id ?? raw.courseId ?? raw.context_id;
  const dueAt =
    raw.due_at ?? raw.due_date ?? raw.end_at ?? raw.end_date ?? raw.close_at ?? raw.deadline ?? null;

  const title = raw.title ?? raw.name ?? raw.display_name ?? raw.activity_name;
  if (!title) return null;

  return normalize(
    {
      id: `lx:${raw.id ?? raw.item_id ?? `${courseId}:${title}`}`,
      source: SOURCE,
      courseId,
      courseName: courseNames.get(String(courseId)) || raw.course_name || raw.context_name,
      title,
      type: typeFromLearningX(raw),
      dueAt,
      postedAt: raw.posted_at ?? raw.created_at ?? raw.reg_date ?? null,
      startAt: raw.start_at ?? raw.unlock_at ?? raw.open_at ?? null,
      url: raw.html_url ? joinUrl(origin, raw.html_url) : courseId ? joinUrl(origin, `/courses/${courseId}`) : '',
      submitted: readDone(raw),
      points: raw.points ?? raw.points_possible ?? null,
    },
    { source: SOURCE }
  );
}

async function firstWorking(origin, paths, signal) {
  for (const path of paths) {
    const res = await tryOr(() => getJSON(joinUrl(origin, path), { signal }));
    if (res) {
      const arr = extractArray(res.data);
      if (arr.length) return { path, items: arr };
    }
  }
  return null;
}

export const learningxAdapter = {
  id: 'learningx',
  label: 'LearningX 확장 API (Canvas 포함)',

  async detect(origin, signal) {
    // LearningX면 Canvas API도 같이 살아있는 게 보통이다.
    if (await canvasAdapter.detect(origin, signal)) return true;
    const hit = await firstWorking(origin, TODO_CANDIDATES, signal);
    return Boolean(hit);
  },

  async fetchAll(origin, opts) {
    const { signal } = opts;

    // 1) Canvas 쪽에서 긁을 수 있는 건 다 긁는다 (가장 신뢰도 높음).
    const canvasItems = (await tryOr(() => canvasAdapter.fetchAll(origin, opts), [])) || [];

    // 2) LearningX 고유 항목을 보조로 얹는다.
    const courseNames = await fetchCourses(origin, signal);

    const todo = await firstWorking(origin, TODO_CANDIDATES, signal);
    const lxTodo = (todo?.items || [])
      .map((raw) => toAssignment(raw, origin, courseNames))
      .filter(Boolean);

    const perCourse = await Promise.all(
      [...courseNames.keys()].map(async (courseId) => {
        const paths = COURSE_ACTIVITY_CANDIDATES.map((p) => p.replace('{courseId}', courseId));
        const hit = await firstWorking(origin, paths, signal);
        return (hit?.items || [])
          .map((raw) => toAssignment({ course_id: courseId, ...raw }, origin, courseNames))
          .filter(Boolean);
      })
    );

    const all = [...canvasItems, ...lxTodo, ...perCourse.flat()];
    if (!all.length) {
      throw new Error(
        'LearningX API에서 과제를 찾지 못했습니다. docs/DISCOVERY.md를 보고 실제 엔드포인트를 확인한 뒤 "직접 지정" 어댑터에 넣어주세요.'
      );
    }
    // LearningX 보조 항목은 노이즈가 크다. 기한이 있거나 종류가 분명한 것만 남긴다.
    // 강의영상은 시청 기한이 안 적혀 있어도 들어야 하므로 반드시 남긴다.
    return all.filter(
      (a) => a.source !== SOURCE || a.dueAt || KEEP_WITHOUT_DUE.has(a.type)
    );
  },
};
