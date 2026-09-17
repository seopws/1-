var s = document.createElement('script');
s.src = "https://cdn.jsdelivr.net/gh/seopws/1-@3834f8551c/bookmarklet/dist/bookmarklet.js";
s.onload = function () { s.remove(); };
s.onerror = function () { alert('과제 한눈에: 코드를 불러오지 못했습니다.'); };
(document.body || document.documentElement).appendChild(s);
completion();
