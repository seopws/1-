import { canvasAdapter } from './canvas.js';
import { learningxAdapter } from './learningx.js';
import { customAdapter } from './custom.js';
import { htmlAdapter } from './html.js';

export const ADAPTERS = [learningxAdapter, canvasAdapter, customAdapter, htmlAdapter];

export function getAdapter(id) {
  return ADAPTERS.find((a) => a.id === id) || null;
}

/**
 * adapter 설정이 'auto'면 되는 걸 순서대로 찾아본다.
 * 순서: 직접 지정 > LearningX > Canvas > HTML (구체적인 설정이 있는 쪽 우선)
 */
export async function resolveAdapter(settings, signal) {
  if (settings.adapter !== 'auto') {
    const a = getAdapter(settings.adapter);
    if (!a) throw new Error(`알 수 없는 어댑터: ${settings.adapter}`);
    return a;
  }

  const order = [customAdapter, learningxAdapter, canvasAdapter, htmlAdapter];
  for (const adapter of order) {
    try {
      if (await adapter.detect(settings.origin, signal, settings)) return adapter;
    } catch {
      // 탐지 실패는 조용히 넘어가고 다음 후보로.
    }
  }
  throw new Error(
    'LMS에 붙을 방법을 자동으로 찾지 못했습니다. LMS에 로그인되어 있는지 확인하고, 안 되면 옵션에서 어댑터를 직접 고르세요.'
  );
}
