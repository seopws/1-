const $ = (id) => document.getElementById(id);

function send(type, extra = {}) {
  return chrome.runtime.sendMessage({ type, ...extra }).then((r) => {
    if (!r?.ok) throw new Error(r?.error || '요청 실패');
    return r.data;
  });
}

function toast(message, isError = false) {
  const box = $('toast');
  box.hidden = false;
  box.textContent = message;
  box.className = isError ? 'banner' : 'banner info';
  clearTimeout(toast._t);
  toast._t = setTimeout(() => (box.hidden = true), 6000);
}

// ---------------------------------------------------------------- 폼 <-> 설정

const FIELD_MAP = {
  origin: 'origin',
  adapter: 'adapter',
  refreshMinutes: 'refreshMinutes',
  lookAheadDays: 'lookAheadDays',
  lookBackDays: 'lookBackDays',
  c_itemsUrl: 'custom.itemsUrl',
  c_itemsPath: 'custom.itemsPath',
  c_coursesUrl: 'custom.coursesUrl',
  c_coursesPath: 'custom.coursesPath',
  m_id: 'custom.map.id',
  m_title: 'custom.map.title',
  m_dueAt: 'custom.map.dueAt',
  m_url: 'custom.map.url',
  m_courseName: 'custom.map.courseName',
  m_submitted: 'custom.map.submitted',
  cm_id: 'custom.courseMap.id',
  cm_name: 'custom.courseMap.name',
  h_listUrl: 'html.listUrl',
  h_rowSelector: 'html.rowSelector',
  h_titleSelector: 'html.titleSelector',
  h_dueSelector: 'html.dueSelector',
  h_courseSelector: 'html.courseSelector',
  h_linkSelector: 'html.linkSelector',
};

function readPath(obj, path) {
  return path.split('.').reduce((cur, k) => (cur == null ? cur : cur[k]), obj);
}

function writePath(obj, path, value) {
  const keys = path.split('.');
  const last = keys.pop();
  let cur = obj;
  for (const k of keys) cur = cur[k] ??= {};
  cur[last] = value;
}

async function loadSettings() {
  const { settings } = await send('getState');

  for (const [id, path] of Object.entries(FIELD_MAP)) {
    const v = readPath(settings, path);
    if (v !== undefined && v !== null) $(id).value = v;
  }
  $('notifyEnabled').checked = settings.notify.enabled;
  $('notifyOffsets').value = settings.notify.offsetsMinutes.join(', ');
  $('hideSubmitted').checked = settings.hideSubmitted;

  await refreshPermState();
  return settings;
}

function collect() {
  const patch = {};
  for (const [id, path] of Object.entries(FIELD_MAP)) {
    const raw = $(id).value.trim();
    if ($(id).type === 'number') {
      const n = Number(raw);
      if (raw !== '' && Number.isFinite(n)) writePath(patch, path, n); // 0도 유효한 값
    } else {
      writePath(patch, path, raw);
    }
  }
  if (patch.origin) patch.origin = patch.origin.replace(/\/+$/, '');

  patch.hideSubmitted = $('hideSubmitted').checked;
  patch.notify = {
    enabled: $('notifyEnabled').checked,
    offsetsMinutes: $('notifyOffsets')
      .value.split(',')
      .map((s) => Number(s.trim()))
      .filter((n) => Number.isFinite(n) && n > 0),
  };
  if (!patch.notify.offsetsMinutes.length) patch.notify.offsetsMinutes = [1440, 180];

  return patch;
}

// ---------------------------------------------------------------- 권한

function originPattern(value) {
  try {
    return `${new URL(value).origin}/*`;
  } catch {
    return null;
  }
}

async function refreshPermState() {
  const pattern = originPattern($('origin').value.trim());
  if (!pattern) {
    $('permState').textContent = 'LMS 주소를 입력하세요. (예: https://lms.myuniv.ac.kr)';
    return false;
  }
  const granted = await chrome.permissions.contains({ origins: [pattern] });
  $('permState').textContent = granted
    ? `✓ ${pattern} 접근 권한이 허용되어 있습니다.`
    : `아직 ${pattern} 접근 권한이 없습니다. "권한 허용"을 눌러주세요.`;
  return granted;
}

$('grant').addEventListener('click', async () => {
  const pattern = originPattern($('origin').value.trim());
  if (!pattern) return toast('LMS 주소 형식이 올바르지 않습니다. https:// 부터 넣어주세요.', true);

  // permissions.request 는 사용자 제스처 안에서만 동작한다.
  const granted = await chrome.permissions.request({ origins: [pattern] });
  await refreshPermState();
  toast(granted ? '권한이 허용되었습니다.' : '권한이 거부되었습니다.', !granted);
});

$('origin').addEventListener('change', refreshPermState);

// ---------------------------------------------------------------- 저장 / 테스트

$('save').addEventListener('click', async () => {
  try {
    await send('saveSettings', { patch: collect() });
    $('saveState').textContent = `저장됨 · ${new Date().toLocaleTimeString('ko-KR')}`;
    toast('설정을 저장했습니다.');
  } catch (err) {
    toast(err.message, true);
  }
});

$('test').addEventListener('click', async (e) => {
  e.currentTarget.disabled = true;
  const out = $('testOut');
  out.hidden = false;
  out.textContent = '테스트 중…';

  try {
    await send('saveSettings', { patch: collect() });
    const r = await send('testAdapter', { adapterId: $('adapter').value });
    out.textContent =
      `✓ ${r.adapter} 어댑터로 ${r.count}개를 찾았습니다.\n\n` +
      r.sample
        .map((a) => `· [${a.courseName}] ${a.title}\n  마감: ${a.dueAt || '없음'}  유형: ${a.type}`)
        .join('\n');
  } catch (err) {
    out.textContent = `✗ ${err.message}`;
  } finally {
    e.currentTarget.disabled = false;
  }
});

$('syncNow').addEventListener('click', async (e) => {
  e.currentTarget.disabled = true;
  try {
    await send('saveSettings', { patch: collect() });
    const r = await send('sync');
    toast(`${r.count}개 과제를 가져왔습니다. (${r.adapter})`);
  } catch (err) {
    toast(err.message, true);
  } finally {
    e.currentTarget.disabled = false;
  }
});

// ---------------------------------------------------------------- 탐색

$('captureOn').addEventListener('click', async () => {
  try {
    const pattern = originPattern($('origin').value.trim());
    if (!pattern) return toast('먼저 LMS 주소를 입력하고 권한을 허용하세요.', true);
    if (!(await chrome.permissions.contains({ origins: [pattern] }))) {
      return toast('먼저 "권한 허용"을 눌러주세요.', true);
    }
    await send('saveSettings', { patch: collect() });
    await send('capture:setEnabled', { enabled: true });
    toast('탐색을 켰습니다. 이제 LMS에서 과제/할 일 목록 페이지를 열어보세요.');
  } catch (err) {
    toast(err.message, true);
  }
});

$('captureOff').addEventListener('click', async () => {
  await send('capture:setEnabled', { enabled: false });
  toast('탐색을 껐습니다.');
});

$('captureRefresh').addEventListener('click', renderCaptures);

$('captureClear').addEventListener('click', async () => {
  await send('capture:clear');
  renderCaptures();
});

async function renderCaptures() {
  const list = await send('capture:list');
  const box = $('captures');
  box.textContent = '';

  if (!list.length) {
    const p = document.createElement('p');
    p.className = 'muted';
    p.textContent = '아직 잡힌 후보가 없습니다. 탐색을 켠 상태로 LMS 과제 목록 페이지를 새로고침해 보세요.';
    box.append(p);
    return;
  }

  for (const entry of list) {
    const div = document.createElement('div');
    div.className = 'capture';

    const u = document.createElement('div');
    u.className = 'u';
    u.textContent = `${entry.method} ${entry.url}`;

    const f = document.createElement('div');
    f.className = 'f';
    f.textContent =
      `날짜 필드: ${entry.dateFields.join(', ') || '-'}` +
      (entry.arrayPath ? ` · 배열 위치: ${entry.arrayPath}` : '') +
      ` · 필드: ${entry.sampleKeys.slice(0, 10).join(', ')}`;

    const apply = document.createElement('button');
    apply.textContent = '이 엔드포인트로 설정 채우기';
    apply.addEventListener('click', () => applyCapture(entry));

    const peek = document.createElement('button');
    peek.textContent = '샘플 보기';
    peek.addEventListener('click', () => {
      $('testOut').hidden = false;
      $('testOut').textContent = JSON.stringify(entry.sample, null, 2);
      $('testOut').scrollIntoView({ behavior: 'smooth', block: 'center' });
    });

    div.append(u, f, apply, document.createTextNode(' '), peek);
    box.append(div);
  }
}

/** 탐색 결과를 직접 지정 어댑터 폼에 자동으로 채워 넣는다. */
function applyCapture(entry) {
  const origin = $('origin').value.trim();
  let path = entry.url;
  try {
    const u = new URL(entry.url);
    if (!origin || u.origin === new URL(origin).origin) path = u.pathname + u.search;
  } catch {}

  // 과목 id가 URL에 박혀 있으면 {courseId} 자리표시자로 바꿔준다.
  path = path.replace(/\/courses\/\d+/, '/courses/{courseId}');

  $('c_itemsUrl').value = path;
  $('c_itemsPath').value = (entry.arrayPath || '').replace(/\[\]$/, '');

  const keys = entry.sampleKeys || [];
  const firstOf = (candidates) => candidates.find((k) => keys.includes(k)) || '';

  $('m_title').value = firstOf(['title', 'name', 'display_name', 'activity_name']);
  $('m_dueAt').value = entry.dateFields[0] || firstOf(['due_at', 'due_date', 'end_at', 'deadline']);
  $('m_id').value = firstOf(['id', 'item_id', 'assignment_id']);
  $('m_url').value = firstOf(['html_url', 'url', 'link']);
  $('m_courseName').value = firstOf(['course_name', 'context_name', 'courseName']);
  $('m_submitted').value = firstOf(['is_submitted', 'submitted', 'is_completed', 'completed']);
  $('adapter').value = 'custom';

  if (path.includes('{courseId}') && !$('c_coursesUrl').value) {
    $('c_coursesUrl').value = '/api/v1/courses?enrollment_state=active&per_page=100';
    $('cm_id').value = 'id';
    $('cm_name').value = 'name';
  }

  toast('설정을 채웠습니다. 비어 있는 칸이 있으면 "샘플 보기"로 확인한 뒤 채우고, 연결 테스트를 눌러보세요.');
  $('customCard').scrollIntoView({ behavior: 'smooth' });
}

loadSettings().then(renderCaptures);
