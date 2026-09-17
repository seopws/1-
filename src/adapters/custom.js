// "직접 지정" 어댑터.
//
// docs/DISCOVERY.md 대로 개발자도구에서 진짜 엔드포인트를 찾아낸 뒤,
// 옵션 화면에 URL과 필드 경로만 적으면 코드를 안 고치고도 학교 LMS에 맞출 수 있다.
// 이 어댑터가 사실상 "어느 학교든 대응 가능"하게 해주는 탈출구다.

import { getJSON, pick, tryOr, joinUrl } from '../lib/http.js';
import { normalize } from '../lib/model.js';

const SOURCE = 'custom';

function arrayAt(data, path) {
  const v = path ? pick(data, path) : data;
  return Array.isArray(v) ? v : [];
}

function mapItem(raw, map, origin, courseName, courseId) {
  const title = pick(raw, map.title);
  if (!title) return null;

  const submittedRaw = map.submitted ? pick(raw, map.submitted) : undefined;
  const url = map.url ? pick(raw, map.url) : '';

  return normalize(
    {
      id: `custom:${pick(raw, map.id) ?? title}`,
      source: SOURCE,
      courseId: (map.courseId ? pick(raw, map.courseId) : undefined) ?? courseId,
      courseName: (map.courseName ? pick(raw, map.courseName) : undefined) ?? courseName,
      title,
      type: map.type ? String(pick(raw, map.type) || 'other') : 'other',
      dueAt: map.dueAt ? pick(raw, map.dueAt) : null,
      url: url ? joinUrl(origin, String(url)) : '',
      submitted:
        submittedRaw === undefined || submittedRaw === null
          ? null
          : Boolean(submittedRaw) && submittedRaw !== 'N' && submittedRaw !== '0',
    },
    { source: SOURCE }
  );
}

export const customAdapter = {
  id: 'custom',
  label: '직접 지정 (내가 찾은 엔드포인트)',

  async detect(origin, signal, settings) {
    return Boolean(settings?.custom?.itemsUrl);
  },

  async fetchAll(origin, opts) {
    const cfg = opts.settings.custom;
    if (!cfg.itemsUrl) throw new Error('옵션에서 "과제 목록 URL"을 먼저 입력하세요.');

    const map = cfg.map;

    // {courseId} 자리표시자가 있으면 과목 목록을 먼저 받아서 과목별로 돈다.
    const needsCourses = cfg.itemsUrl.includes('{courseId}');
    let courses = [{ id: '', name: '' }];

    if (needsCourses) {
      if (!cfg.coursesUrl) throw new Error('{courseId}를 쓰려면 "과목 목록 URL"도 필요합니다.');
      const { data } = await getJSON(joinUrl(origin, cfg.coursesUrl), { signal: opts.signal });
      courses = arrayAt(data, cfg.coursesPath).map((c) => ({
        id: String(pick(c, cfg.courseMap.id) ?? ''),
        name: String(pick(c, cfg.courseMap.name) ?? ''),
      }));
      if (!courses.length) throw new Error('과목 목록이 비어 있습니다. "과목 배열 경로"를 확인하세요.');
    }

    const perCourse = await Promise.all(
      courses.map(async (c) => {
        const url = joinUrl(origin, cfg.itemsUrl.replaceAll('{courseId}', c.id));
        const res = await tryOr(() => getJSON(url, { signal: opts.signal }));
        if (!res) return [];
        return arrayAt(res.data, cfg.itemsPath)
          .map((raw) => mapItem(raw, map, origin, c.name, c.id))
          .filter(Boolean);
      })
    );

    const all = perCourse.flat();
    if (!all.length) {
      throw new Error(
        '요청은 성공했지만 항목이 비어 있습니다. "과제 배열 경로"와 "제목 필드" 매핑을 확인하세요.'
      );
    }
    return all;
  },
};
