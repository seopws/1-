// 백그라운드: 주기적 동기화, 마감 알림, 팝업/대시보드/옵션의 요청 처리.

import { getSettings, saveSettings, getState, setState, STATE_KEYS } from '../lib/storage.js';
import { resolveAdapter, getAdapter } from '../adapters/index.js';
import { dedupe, sortByDue, ddayInfo } from '../lib/model.js';
import { NotLoggedInError } from '../lib/http.js';
import { registerCaptureScripts, unregisterCaptureScripts, recordCapture } from './capture.js';

const ALARM_SYNC = 'sync';
const ALARM_NOTIFY = 'notify';

chrome.runtime.onInstalled.addListener(async () => {
  await rescheduleAlarms();

  // 기본값(중앙대 e-Class)이 이미 들어 있으므로 설정 화면을 띄우지 않고 바로 한 번 긁어온다.
  // e-Class에 로그인이 안 돼 있으면 lastError에 안내가 남고 팝업에 표시된다.
  const { origin } = await getSettings();
  if (!origin) {
    chrome.runtime.openOptionsPage();
    return;
  }
  await sync({ reason: 'install' }).catch(() => {});
});

chrome.runtime.onStartup.addListener(rescheduleAlarms);

chrome.alarms.onAlarm.addListener(async (alarm) => {
  if (alarm.name === ALARM_SYNC) await sync({ reason: 'alarm' }).catch(() => {});
  if (alarm.name === ALARM_NOTIFY) await checkNotifications().catch(() => {});
});

chrome.notifications.onClicked.addListener((id) => {
  openDashboard();
  chrome.notifications.clear(id);
});

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg?.target === 'offscreen') return false; // offscreen 문서 몫

  handleMessage(msg, sender)
    .then((data) => sendResponse({ ok: true, data }))
    .catch((err) => sendResponse({ ok: false, error: toUserMessage(err) }));
  return true; // 비동기 응답
});

async function handleMessage(msg, sender) {
  switch (msg?.type) {
    case 'sync':
      return sync({ reason: 'manual' });
    case 'getState':
      return { ...(await getState()), settings: await getSettings() };
    case 'saveSettings': {
      const settings = await saveSettings(msg.patch);
      await rescheduleAlarms();
      await refreshCaptureRegistration(settings);
      return settings;
    }
    case 'testAdapter':
      return testAdapter(msg.adapterId);
    case 'capture:record':
      return recordCapture(msg.entry, sender);
    case 'capture:list':
      return (await chrome.storage.local.get(STATE_KEYS.captured))[STATE_KEYS.captured] || [];
    case 'capture:clear':
      await chrome.storage.local.set({ [STATE_KEYS.captured]: [] });
      return true;
    case 'capture:setEnabled':
      return msg.enabled ? registerCaptureScripts(await getSettings()) : unregisterCaptureScripts();
    case 'openDashboard':
      return openDashboard();
    default:
      throw new Error(`알 수 없는 요청: ${msg?.type}`);
  }
}

// ---------------------------------------------------------------- 동기화

let inFlight = null;

export async function sync({ reason = 'manual' } = {}) {
  if (inFlight) return inFlight;
  inFlight = doSync(reason).finally(() => {
    inFlight = null;
  });
  return inFlight;
}

async function doSync(reason) {
  const settings = await getSettings();

  if (!settings.origin) {
    const err = new Error('학교 LMS 주소가 아직 설정되지 않았습니다. 옵션에서 입력해 주세요.');
    await setState({ [STATE_KEYS.lastError]: err.message });
    throw err;
  }

  if (!(await hasOriginPermission(settings.origin))) {
    const err = new Error('LMS 접근 권한이 없습니다. 옵션 화면에서 "권한 허용"을 눌러주세요.');
    await setState({ [STATE_KEYS.lastError]: err.message });
    throw err;
  }

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 60_000);

  try {
    const adapter = await resolveAdapter(settings, controller.signal);
    const raw = await adapter.fetchAll(settings.origin, {
      signal: controller.signal,
      settings,
      lookAheadDays: settings.lookAheadDays,
      lookBackDays: settings.lookBackDays,
    });

    const cutoffPast = Date.now() - settings.lookBackDays * 86400000;
    const assignments = sortByDue(
      dedupe(raw).filter((a) => !a.dueAt || new Date(a.dueAt).getTime() >= cutoffPast)
    );

    await setState({
      [STATE_KEYS.assignments]: assignments,
      [STATE_KEYS.lastSync]: { at: Date.now(), reason, adapter: adapter.id, count: assignments.length },
      [STATE_KEYS.lastError]: null,
    });

    await updateBadge(assignments);
    await checkNotifications();

    return { count: assignments.length, adapter: adapter.id };
  } catch (err) {
    await setState({ [STATE_KEYS.lastError]: toUserMessage(err) });
    throw err;
  } finally {
    clearTimeout(timeout);
  }
}

async function testAdapter(adapterId) {
  const settings = await getSettings();
  const adapter = adapterId === 'auto'
    ? await resolveAdapter(settings)
    : getAdapter(adapterId);
  if (!adapter) throw new Error(`알 수 없는 어댑터: ${adapterId}`);

  const items = await adapter.fetchAll(settings.origin, {
    settings,
    lookAheadDays: settings.lookAheadDays,
    lookBackDays: settings.lookBackDays,
  });
  const clean = sortByDue(dedupe(items));
  return { adapter: adapter.id, count: clean.length, sample: clean.slice(0, 5) };
}

// ---------------------------------------------------------------- 뱃지 / 알림

async function updateBadge(assignments) {
  const now = Date.now();
  // 공지·강의자료는 마감이 없다. 뱃지 숫자에 섞이면 안 된다.
  const soon = assignments.filter(
    (a) =>
      a.kind === 'task' &&
      a.dueAt &&
      !a.submitted &&
      new Date(a.dueAt).getTime() >= now &&
      ddayInfo(a.dueAt, now).days <= 3
  ).length;

  await chrome.action.setBadgeText({ text: soon ? String(soon) : '' });
  await chrome.action.setBadgeBackgroundColor({ color: soon ? '#d92d20' : '#667085' });
}

async function checkNotifications() {
  const settings = await getSettings();
  if (!settings.notify.enabled) return;

  const { assignments } = await getState();
  const store = await chrome.storage.local.get(STATE_KEYS.notified);
  const notified = new Set(store[STATE_KEYS.notified] || []);
  const now = Date.now();
  let changed = false;

  for (const a of assignments) {
    if (a.kind !== 'task' || !a.dueAt || a.submitted) continue;
    const minutesLeft = (new Date(a.dueAt).getTime() - now) / 60000;
    if (minutesLeft < 0) continue;

    for (const offset of settings.notify.offsetsMinutes) {
      // 알람은 최대 1분 간격으로 돈다. 창을 살짝 넓게 잡아 놓치지 않게.
      if (minutesLeft > offset || minutesLeft < offset - 30) continue;

      const key = `${a.id}@${offset}`;
      if (notified.has(key)) continue;

      chrome.notifications.create(`due:${a.id}`, {
        type: 'basic',
        iconUrl: chrome.runtime.getURL('src/icons/icon128.png'),
        title: `${ddayInfo(a.dueAt, now).label} — ${a.courseName}`,
        message: a.title,
        priority: 2,
      });
      notified.add(key);
      changed = true;
    }
  }

  if (changed) {
    // 오래된 키가 무한정 쌓이지 않게 잘라준다.
    await chrome.storage.local.set({ [STATE_KEYS.notified]: [...notified].slice(-500) });
  }
}

// ---------------------------------------------------------------- 잡일

async function rescheduleAlarms() {
  const settings = await getSettings();
  await chrome.alarms.clearAll();
  chrome.alarms.create(ALARM_SYNC, {
    periodInMinutes: Math.max(15, settings.refreshMinutes),
    delayInMinutes: 1,
  });
  chrome.alarms.create(ALARM_NOTIFY, { periodInMinutes: 15, delayInMinutes: 1 });
}

async function refreshCaptureRegistration(settings) {
  const registered = await chrome.scripting.getRegisteredContentScripts().catch(() => []);
  if (registered.some((script) => script.id === 'capture-main')) {
    await unregisterCaptureScripts();
    await registerCaptureScripts(settings);
  }
}

export async function hasOriginPermission(origin) {
  if (!origin) return false;
  try {
    return await chrome.permissions.contains({ origins: [`${new URL(origin).origin}/*`] });
  } catch {
    return false;
  }
}

function openDashboard() {
  return chrome.tabs.create({ url: chrome.runtime.getURL('src/dashboard/dashboard.html') });
}

function toUserMessage(err) {
  if (err instanceof NotLoggedInError || err?.name === 'NotLoggedInError') {
    return 'LMS 로그인 세션이 만료됐습니다. LMS 탭에서 로그인한 뒤 다시 새로고침하세요.';
  }
  if (err?.name === 'AbortError') return '요청이 너무 오래 걸려 중단했습니다.';
  return String(err?.message || err);
}
