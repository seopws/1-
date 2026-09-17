// 과제 목록을 .ics(iCalendar)로 내보낸다. 구글/애플 캘린더에 그대로 import 가능.

function esc(text) {
  return String(text ?? '')
    .replace(/\\/g, '\\\\')
    .replace(/;/g, '\;')
    .replace(/,/g, '\\,')
    .replace(/\r?\n/g, '\\n');
}

function stamp(date) {
  return new Date(date).toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');
}

/** RFC 5545: 한 줄 75옥텟 제한. 넘으면 접어준다. */
function fold(line) {
  if (line.length <= 73) return line;
  const parts = [line.slice(0, 73)];
  let rest = line.slice(73);
  while (rest.length > 72) {
    parts.push(' ' + rest.slice(0, 72));
    rest = rest.slice(72);
  }
  if (rest) parts.push(' ' + rest);
  return parts.join('\r\n');
}

export function toICS(assignments, { calendarName = '과제 마감일' } = {}) {
  const lines = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//gwaje-hannune//LearningX Deadline Board//KO',
    'CALSCALE:GREGORIAN',
    `X-WR-CALNAME:${esc(calendarName)}`,
  ];

  for (const a of assignments) {
    if (!a.dueAt) continue;
    const due = new Date(a.dueAt);
    lines.push(
      'BEGIN:VEVENT',
      `UID:${esc(a.id)}@gwaje-hannune`,
      `DTSTAMP:${stamp(Date.now())}`,
      // 마감 시각 기준 30분짜리 일정으로 넣는다.
      `DTSTART:${stamp(due.getTime() - 30 * 60000)}`,
      `DTEND:${stamp(due)}`,
      `SUMMARY:${esc(`[${a.courseName}] ${a.title}`)}`,
      `DESCRIPTION:${esc(`${a.courseName} · 마감 ${due.toLocaleString('ko-KR')}${a.url ? `\n${a.url}` : ''}`)}`,
      a.url ? `URL:${esc(a.url)}` : null,
      'BEGIN:VALARM',
      'TRIGGER:-P1D',
      'ACTION:DISPLAY',
      `DESCRIPTION:${esc(`내일 마감 — ${a.title}`)}`,
      'END:VALARM',
      'END:VEVENT'
    );
  }

  lines.push('END:VCALENDAR');
  return lines.filter(Boolean).map(fold).join('\r\n');
}

export function downloadICS(assignments, filename = 'assignments.ics') {
  const blob = new Blob([toICS(assignments)], { type: 'text/calendar;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
