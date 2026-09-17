// chrome.storage 래퍼 + 기본 설정값.

export const DEFAULT_SETTINGS = {
  // 중앙대 e-Class. manifest의 host_permissions에도 같은 도메인이 선언돼 있어서
  // 설치 직후 별도 설정 없이 바로 동작한다.
  origin: 'https://eclass3.cau.ac.kr',
  adapter: 'auto',            // auto | canvas | learningx | custom | html
  lookAheadDays: 60,
  lookBackDays: 14,
  refreshMinutes: 30,
  hideSubmitted: false,
  notify: {
    enabled: true,
    offsetsMinutes: [24 * 60, 3 * 60], // D-1, 마감 3시간 전
  },
  custom: {
    itemsUrl: '',      // 과제 목록 JSON 엔드포인트. {courseId} 치환 지원
    coursesUrl: '',    // (선택) 수강 과목 목록 JSON
    itemsPath: '',     // 응답에서 배열 위치. 최상위가 배열이면 비워둔다
    coursesPath: '',
    map: {             // JSON 필드 → 공통 모델 필드 경로
      id: 'id',
      title: 'title',
      dueAt: 'due_at',
      courseId: 'course_id',
      courseName: 'course_name',
      url: 'html_url',
      submitted: '',
      type: '',
    },
    courseMap: { id: 'id', name: 'name' },
  },
  html: {
    listUrl: '',         // 과제 목록 페이지 URL. {courseId} 치환 지원
    rowSelector: '',
    titleSelector: '',
    dueSelector: '',
    linkSelector: 'a',
    courseSelector: '',
  },
};

export const STATE_KEYS = {
  settings: 'settings',
  assignments: 'assignments',
  lastSync: 'lastSync',
  lastError: 'lastError',
  notified: 'notified',
  captured: 'capturedEndpoints',
};

function deepMerge(base, patch) {
  if (patch === undefined || patch === null) return base;
  if (Array.isArray(base) || typeof base !== 'object') return patch;
  const out = { ...base };
  for (const [k, v] of Object.entries(patch)) {
    out[k] = k in base ? deepMerge(base[k], v) : v;
  }
  return out;
}

export async function getSettings() {
  const stored = await chrome.storage.local.get(STATE_KEYS.settings);
  return deepMerge(DEFAULT_SETTINGS, stored[STATE_KEYS.settings] || {});
}

export async function saveSettings(patch) {
  const merged = deepMerge(await getSettings(), patch);
  await chrome.storage.local.set({ [STATE_KEYS.settings]: merged });
  return merged;
}

export async function getState() {
  const s = await chrome.storage.local.get([
    STATE_KEYS.assignments,
    STATE_KEYS.lastSync,
    STATE_KEYS.lastError,
  ]);
  return {
    assignments: s[STATE_KEYS.assignments] || [],
    lastSync: s[STATE_KEYS.lastSync] || null,
    lastError: s[STATE_KEYS.lastError] || null,
  };
}

export async function setState(patch) {
  await chrome.storage.local.set(patch);
}
