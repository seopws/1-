// 북마클릿 빌드: 확장과 공유하는 모듈을 하나로 묶고 최소화한 뒤, javascript: URL로 감싼다.
import { build } from 'esbuild';
import { writeFile, mkdir } from 'node:fs/promises';

const REPO = 'seopws/1-';
const OUT_DIR = new URL('./dist/', import.meta.url);
await mkdir(OUT_DIR, { recursive: true });

// esbuild는 템플릿 문자열 안의 CSS를 그대로 둔다. 직접 눌러준다.
const squeezeCss = {
  name: 'squeeze-css',
  setup(pluginBuild) {
    pluginBuild.onLoad({ filter: /main\.js$/ }, async (args) => {
      const { readFile } = await import('node:fs/promises');
      let source = await readFile(args.path, 'utf8');

      source = source.replace(/const CSS = `([\s\S]*?)`;/, (_, css) => {
        const packed = css
          .replace(/\/\*[\s\S]*?\*\//g, '')
          .replace(/\s*([{}:;,>])\s*/g, '$1')
          .replace(/\s+/g, ' ')
          .replace(/;}/g, '}')
          .trim();
        return `const CSS = ${JSON.stringify(packed)};`;
      });

      return { contents: source, loader: 'js' };
    });
  },
};

const result = await build({
  entryPoints: [new URL('./src/main.js', import.meta.url).pathname],
  bundle: true,
  minify: true,
  format: 'iife',
  target: ['safari15', 'chrome100'],
  charset: 'utf8',
  legalComments: 'none',
  plugins: [squeezeCss],
  write: false,
});

const code = result.outputFiles[0].text.trim();

// javascript: URL에서 실제로 문제가 되는 문자만 인코딩한다.
// 전부 encodeURIComponent 하면 한글 한 글자가 9바이트로 불어나 URL이 배로 길어진다.
//   %  → URL 이스케이프 시작으로 먹힘 (반드시 먼저)
//   #  → 프래그먼트 구분자로 잘림
//   공백/개행/탭 → 주소창 입력 시 잘리거나 사라질 수 있음
function toBookmarkletUrl(js) {
  const escaped = js
    .replace(/%/g, '%25')
    .replace(/#/g, '%23')
    .replace(/ /g, '%20')
    .replace(/\n/g, '%0A')
    .replace(/\r/g, '%0D')
    .replace(/\t/g, '%09');
  // void(0)이 없으면 페이지가 스크립트 반환값으로 교체되는 브라우저가 있다.
  return `javascript:${escaped}void(0);`;
}

const bookmarklet = toBookmarkletUrl(`(function(){${code}})();`);

// ── 짧은 로더 ──────────────────────────────────────────────────────────────
// 19KB짜리 문자열을 아이패드에서 손으로 복사하는 건 사실상 불가능하다.
// 번들은 jsDelivr로 서빙하고, 북마크에는 그걸 불러오는 몇 줄만 넣는다.
//
// jsDelivr는 브랜치 이름에 슬래시가 있으면 경로를 잘못 끊으므로 커밋 SHA로 고정한다.
// SHA 고정은 캐시도 영구적이라 오히려 유리하다.
const { execFileSync } = await import('node:child_process');

// 새 번들을 커밋하기 전에는 가리킬 SHA가 없다. 1단계 빌드용 탈출구.
const skipLoader = process.argv.includes('--no-loader');

const ref = (() => {
  if (skipLoader) return null;
  const i = process.argv.indexOf('--ref');
  if (i !== -1 && process.argv[i + 1]) return process.argv[i + 1];
  try {
    return execFileSync('git', ['rev-parse', '--short=10', 'HEAD'], { encoding: 'utf8' }).trim();
  } catch {
    return null;
  }
})();

let loader = null;
let shortcut = null;

if (ref) {
  // 로더가 낡은 코드를 가리키면 조용히 옛날 동작을 하게 된다. 그게 제일 고약하다.
  let published = null;
  try {
    published = execFileSync('git', ['show', `${ref}:bookmarklet/dist/bookmarklet.js`], {
      encoding: 'utf8',
      maxBuffer: 1024 * 1024 * 8,
    });
  } catch {
    published = null;
  }

  if (published === null) {
    console.warn(`⚠ ${ref} 에 bookmarklet.js 가 없습니다. 먼저 커밋한 뒤 --ref 로 그 SHA를 넘기세요.`);
  } else if (published.trim() !== code.trim()) {
    console.error(
      `✗ ${ref} 의 번들이 방금 빌드한 것과 다릅니다.\n` +
        '  로더가 낡은 코드를 가리키게 됩니다. 새 번들을 커밋한 뒤 그 SHA로 다시 빌드하세요.'
    );
    process.exit(1);
  } else {
    const src = `https://cdn.jsdelivr.net/gh/${REPO}@${ref}/bookmarklet/dist/bookmarklet.js`;
    loader = toBookmarkletUrl(
      `(function(){var s=document.createElement('script');` +
        `s.src=${JSON.stringify(src)};` +
        `s.onload=function(){s.remove()};` +
        `s.onerror=function(){alert('과제 한눈에: 코드를 불러오지 못했습니다. 인터넷 연결을 확인하거나, 설치 페이지의 전체 버전을 쓰세요.')};` +
        `(document.body||document.documentElement).appendChild(s)})();`
    );
    await writeFile(new URL('./loader.txt', OUT_DIR), loader);

    // iOS 단축어의 "웹 페이지에서 JavaScript 실행" 액션용.
    // javascript: 접두사도 URL 인코딩도 없는 순수 JS여야 하고, completion() 을 반드시 불러야
    // 단축어가 끝나지 않고 멈춘다.
    shortcut = [
      "var s = document.createElement('script');",
      `s.src = ${JSON.stringify(src)};`,
      's.onload = function () { s.remove(); };',
      "s.onerror = function () { alert('과제 한눈에: 코드를 불러오지 못했습니다.'); };",
      '(document.body || document.documentElement).appendChild(s);',
      'completion();',
    ].join('\n');
    await writeFile(new URL('./shortcut.js', OUT_DIR), shortcut + '\n');
  }
}

await writeFile(new URL('./bookmarklet.js', OUT_DIR), code + '\n');
await writeFile(new URL('./bookmarklet.txt', OUT_DIR), bookmarklet);

// 설치 페이지에 방금 만든 북마클릿을 박아 넣는다. 손으로 복사해 두면 반드시 낡는다.
const { readFile } = await import('node:fs/promises');
const template = await readFile(new URL('./src/install.template.html', import.meta.url), 'utf8');

const escapeHtml = (text) =>
  text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

for (const key of ['__BOOKMARKLET__', '__LOADER__', '__SHORTCUT__']) {
  if (!template.includes(key)) {
    console.error(`⚠ 템플릿에 ${key} 자리표시자가 없습니다.`);
    process.exit(1);
  }
}

await writeFile(
  new URL('./install.html', OUT_DIR),
  template
    .replace('__LOADER__', escapeHtml(loader || bookmarklet))
    .replace('__SHORTCUT__', escapeHtml(shortcut || '(로더를 먼저 만들어야 합니다)'))
    .replace('__BOOKMARKLET__', escapeHtml(bookmarklet))
);

const kb = (n) => `${(n / 1024).toFixed(1)}KB`;
console.log(`번들 ${kb(code.length)} → 전체 북마클릿 ${kb(bookmarklet.length)}`);
console.log(
  loader
    ? `로더 ${loader.length}자 (${ref}) → dist/loader.txt`
    : '로더 건너뜀 — 번들을 커밋한 뒤 다시 빌드하세요'
);
console.log('설치 페이지 → dist/install.html');

if (bookmarklet.length > 60000) {
  console.error('⚠ 북마클릿이 너무 깁니다. 브라우저 주소 길이 제한에 걸릴 수 있습니다.');
  process.exit(1);
}
