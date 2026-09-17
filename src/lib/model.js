// 모든 어댑터가 뱉어내는 공통 과제 모델 + 날짜/정렬 유틸.

export const TYPE_LABEL = {
  assignment: '과제',
  quiz: '퀴즈',
  exam: '시험',
  discussion: '토론',
  video: '강의영상',
  attendance: '출석',
  other: '기타',
};

const MS_MIN = 60 * 1000;
const MS_DAY = 24 * 60 * MS_MIN;

/**
 * LMS가 돌려주는 온갖 날짜 표기를 ISO 문자열 하나로 정리한다.
 * 타임존이 안 붙은 값(한국 LMS가 흔히 이렇다)은 브라우저 로컬 시간으로 본다.
 * @returns {string|null} ISO 8601 문자열, 못 읽으면 null
 */
export function parseDue(value) {
  if (value === null || value === undefined || value === '') return null;

  if (value instanceof Date) return isFinite(value) ? value.toISOString() : null;

  if (typeof value === 'number') {
    // 초 단위 epoch도 종종 온다.
    const ms = value < 1e11 ? value * 1000 : value;
    const d = new Date(ms);
    return isFinite(d) ? d.toISOString() : null;
  }

  const raw = String(value).trim();
  if (!raw) return null;

  // 타임존이 명시된 ISO 값은 그대로 믿는다.
  if (/[zZ]$|[+-]\d{2}:?\d{2}$/.test(raw)) {
    const d = new Date(raw);
    if (isFinite(d)) return d.toISOString();
  }

  // 2026-03-14 23:59:59 / 2026.03.14 23:59 / 2026/03/14T23:59
  const m = raw.match(
    /^(\d{4})[-./](\d{1,2})[-./](\d{1,2})(?:[T\s]+(\d{1,2}):(\d{2})(?::(\d{2}))?)?/
  );
  if (m) {
    const [, y, mo, da, h, mi, s] = m;
    const hasTime = h !== undefined;
    const d = new Date(
      Number(y),
      Number(mo) - 1,
      Number(da),
      hasTime ? Number(h) : 23,
      hasTime ? Number(mi) : 59,
      hasTime ? Number(s || 0) : 0
    );
    return isFinite(d) ? d.toISOString() : null;
  }

  const fallback = new Date(raw);
  return isFinite(fallback) ? fallback.toISOString() : null;
}

/** 어댑터 결과를 공통 모델로 맞춘다. 빠진 필드는 안전한 기본값으로 채운다. */
export function normalize(item, ctx = {}) {
  const dueAt = parseDue(item.dueAt ?? item.due_at ?? item.dueDate ?? null);
  const courseName = String(item.courseName ?? ctx.courseName ?? '(과목 미상)').trim();

  return {
    id: String(item.id ?? `${courseName}:${item.title}:${dueAt}`),
    source: item.source ?? ctx.source ?? 'unknown',
    courseId: item.courseId != null ? String(item.courseId) : (ctx.courseId != null ? String(ctx.courseId) : ''),
    courseName,
    title: String(item.title ?? '(제목 없음)').trim(),
    type: TYPE_LABEL[item.type] ? item.type : 'other',
    dueAt,
    startAt: parseDue(item.startAt ?? item.unlock_at ?? null),
    url: item.url ? String(item.url) : '',
    submitted: typeof item.submitted === 'boolean' ? item.submitted : null,
    points: item.points ?? null,
    note: item.note ? String(item.note) : '',
  };
}

/** 같은 과제가 여러 엔드포인트에서 중복으로 잡히는 걸 걸러낸다. */
export function dedupe(list) {
  const seen = new Map();
  for (const a of list) {
    const key = `${a.courseName}|${a.title.replace(/\s+/g, '')}|${a.dueAt ?? ''}`;
    const prev = seen.get(key);
    if (!prev) {
      seen.set(key, a);
      continue;
    }
    // 정보가 더 많은 쪽을 남긴다.
    const score = (x) => (x.url ? 2 : 0) + (x.submitted !== null ? 2 : 0) + (x.type !== 'other' ? 1 : 0);
    if (score(a) > score(prev)) seen.set(key, a);
  }
  return [...seen.values()];
}

/** D-day 계산. 날짜 경계(자정) 기준이라 "내일 오전 9시"도 D-1로 나온다. */
export function ddayInfo(iso, now = Date.now()) {
  if (!iso) return { days: null, minutes: null, label: '기한 없음', tone: 'none' };

  const due = new Date(iso).getTime();
  const minutes = Math.round((due - now) / MS_MIN);

  const startOfToday = new Date(now);
  startOfToday.setHours(0, 0, 0, 0);
  const startOfDue = new Date(due);
  startOfDue.setHours(0, 0, 0, 0);
  const days = Math.round((startOfDue - startOfToday) / MS_DAY);

  let label;
  let tone;
  if (minutes < 0) {
    label = `${formatSpan(-minutes)} 지남`;
    tone = 'overdue';
  } else if (days === 0) {
    label = minutes < 60 ? `${minutes}분 남음` : `${Math.floor(minutes / 60)}시간 남음`;
    tone = 'urgent';
  } else {
    label = `D-${days}`;
    tone = days <= 3 ? 'soon' : days <= 7 ? 'week' : 'later';
  }
  return { days, minutes, label, tone };
}

function formatSpan(minutes) {
  if (minutes < 60) return `${minutes}분`;
  if (minutes < 60 * 24) return `${Math.floor(minutes / 60)}시간`;
  return `${Math.floor(minutes / (60 * 24))}일`;
}

export const BUCKETS = [
  { key: 'overdue', label: '지난 마감' },
  { key: 'today', label: '오늘' },
  { key: 'tomorrow', label: '내일' },
  { key: 'week', label: '이번 주 (7일 내)' },
  { key: 'later', label: '그 이후' },
  { key: 'nodue', label: '기한 없음' },
];

export function bucketOf(item, now = Date.now()) {
  if (!item.dueAt) return 'nodue';
  const { days, minutes } = ddayInfo(item.dueAt, now);
  if (minutes < 0) return 'overdue';
  if (days === 0) return 'today';
  if (days === 1) return 'tomorrow';
  if (days <= 7) return 'week';
  return 'later';
}

/** 마감 빠른 순. 기한 없는 항목은 항상 맨 뒤. */
export function sortByDue(list) {
  return [...list].sort((a, b) => {
    if (!a.dueAt && !b.dueAt) return a.courseName.localeCompare(b.courseName, 'ko');
    if (!a.dueAt) return 1;
    if (!b.dueAt) return -1;
    return new Date(a.dueAt) - new Date(b.dueAt);
  });
}
