// 북마클릿 빌드: 확장과 공유하는 모듈을 하나로 묶고 최소화한 뒤, javascript: URL로 감싼다.
import { build } from 'esbuild';
import { writeFile, mkdir } from 'node:fs/promises';

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

await writeFile(new URL('./bookmarklet.js', OUT_DIR), code + '\n');
await writeFile(new URL('./bookmarklet.txt', OUT_DIR), bookmarklet);

// 설치 페이지에 방금 만든 북마클릿을 박아 넣는다. 손으로 복사해 두면 반드시 낡는다.
const { readFile } = await import('node:fs/promises');
const template = await readFile(new URL('./src/install.template.html', import.meta.url), 'utf8');

const forTextarea = bookmarklet
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;');

if (!template.includes('__BOOKMARKLET__')) {
  console.error('⚠ 템플릿에 __BOOKMARKLET__ 자리표시자가 없습니다.');
  process.exit(1);
}

await writeFile(
  new URL('./install.html', OUT_DIR),
  template.replace('__BOOKMARKLET__', forTextarea)
);

const kb = (n) => `${(n / 1024).toFixed(1)}KB`;
console.log(`번들 ${kb(code.length)} → 북마클릿 URL ${kb(bookmarklet.length)} → 설치 페이지 dist/install.html`);

if (bookmarklet.length > 60000) {
  console.error('⚠ 북마클릿이 너무 깁니다. 브라우저 주소 길이 제한에 걸릴 수 있습니다.');
  process.exit(1);
}
