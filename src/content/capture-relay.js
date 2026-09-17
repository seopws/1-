// ISOLATED world: MAIN world가 postMessage로 던진 후보를 백그라운드로 넘긴다.
window.addEventListener('message', (event) => {
  if (event.source !== window) return;
  const data = event.data;
  if (!data || data.__gwajeCapture !== true) return;
  chrome.runtime.sendMessage({ type: 'capture:record', entry: data.entry }).catch(() => {});
});
