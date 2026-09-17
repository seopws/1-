# 과제 한눈에 — 중앙대 e-Class

중앙대 e-Class(`eclass3.cau.ac.kr`)에 흩어져 있는 모든 과목의 과제 마감일을,
**한 화면에 D-day 순으로** 모아주는 크롬 확장 프로그램. 개인용.

e-Class(LearningX)는 과목별로 들어가야 과제가 보이기 때문에, 수강 과목이 6개면
마감일 하나 확인하는 데 클릭을 스무 번쯤 해야 합니다. 이 확장은 그걸 한 화면으로 줄입니다.

<br>

## 무엇을 하나

- **통합 마감 보드** — 모든 과목의 과제·퀴즈·토론을 마감 빠른 순으로 한 목록에
- **과제와 자료를 분리** — 공지·강의자료는 따로 모아 최근 올라온 순으로. 마감일이 있는 것만 과제로 셉니다
- **D-day 뱃지** — 지난 마감 / 오늘 / D-1 / D-7 색으로 구분
- **마감 알림** — 기본값 하루 전 + 3시간 전 데스크톱 알림
- **툴바 뱃지** — 3일 내 마감 개수를 확장 아이콘에 숫자로 표시
- **캘린더 내보내기** — `.ics` 로 받아 구글/애플 캘린더에 그대로 넣기
- **검색·필터** — 과목별, 유형별, 제출 완료 숨기기

<br>

## 왜 확장 프로그램인가

**학번과 비밀번호를 어디에도 저장하지 않습니다.**

이미 브라우저에 있는 LMS 로그인 세션 쿠키를 그대로 빌려 씁니다(`credentials: 'include'`).
확장이 비밀번호를 알 필요도, 외부 서버로 무언가 보낼 필요도 없습니다.
가져온 과제 목록은 전부 `chrome.storage.local`, 즉 내 컴퓨터 안에만 있습니다.

<br>

## 설치

```bash
git clone <this-repo>
```

1. 크롬에서 `chrome://extensions` 열기
2. 오른쪽 위 **개발자 모드** 켜기
3. **압축해제된 확장 프로그램을 로드합니다** → 이 폴더 선택
4. **끝.** e-Class 주소와 권한이 이미 박혀 있어서 설치 직후 알아서 한 번 긁어옵니다.

빌드 과정도, 설정 화면을 열 일도 없습니다.
단, **e-Class에 로그인되어 있어야** 합니다. 안 되어 있으면 팝업에 그렇게 뜹니다.

<br>

## e-Class가 바뀌면

e-Class는 Canvas LMS 기반(`/courses/123/pages/...`, `/learningx/...`)이라 `/api/v1/*`가 그대로 살아 있고,
**자동 감지**가 바로 됩니다. 학교가 API를 막거나 도메인을 바꾸면 어댑터를 갈아끼우면 됩니다.

| 어댑터 | 언제 쓰나 |
|---|---|
| `learningx` | LearningX 확장 API + Canvas API를 함께 긁음 (기본) |
| `canvas` | Canvas 표준 `/api/v1`만 사용 |
| `custom` | 내가 직접 찾은 엔드포인트 + 필드 매핑 — **API가 바뀌어도 코드 수정 없이 대응하는 탈출구** |
| `html` | API를 끝내 못 찾을 때, 과제 목록 페이지 HTML을 직접 파싱 |

설정 화면의 **엔드포인트 탐색** 기능을 켜고 e-Class 과제 페이지를 한 번 열면,
마감일이 들어 있는 응답을 자동으로 골라내 **설정을 대신 채워줍니다**.
개발자도구를 열 필요가 없습니다.

자세한 절차: **[docs/DISCOVERY.md](docs/DISCOVERY.md)**

> 다른 학교에서 쓰려면 `manifest.json`의 `host_permissions`와
> `src/lib/storage.js`의 `DEFAULT_SETTINGS.origin` 두 줄만 바꾸면 됩니다.

<br>

## 아이패드 · 아이폰 (북마클릿)

iOS·iPadOS의 크롬과 사파리에는 **확장 프로그램을 설치할 수 없습니다.** 애플이 막아둔 것이라 우회 방법이 없습니다.
대신 같은 일을 하는 **북마클릿**을 같이 넣어뒀습니다.

`bookmarklet/dist/install.html` 을 열어 코드를 복사한 뒤, 사파리 북마크 하나의 **주소**를 그것으로 바꾸면 됩니다.
e-Class에 로그인한 상태로 그 북마크를 누르면 과제 목록이 화면 위로 올라옵니다.

두 가지 형태로 나옵니다.

- **로더 (권장, 약 370자)** — 실행할 때 번들을 jsDelivr에서 내려받습니다.
  아이패드에서 19KB를 손으로 복사하는 건 사실상 불가능해서, 이쪽이 기본입니다.
- **전체 버전 (19KB)** — 외부 요청 없이 혼자 동작합니다. CDN이 막힌 환경용.

- 확장과 **같은 어댑터·날짜 계산·ics 생성 코드**를 그대로 번들해서 씁니다. 결과가 갈릴 일이 없습니다.
- 페이지 안에서 실행되므로 로그인 세션을 그대로 빌려 씁니다. 여기서도 비밀번호는 쓰지 않습니다.
- 자동 새로고침과 마감 알림은 없습니다(북마크를 눌러야 실행되므로). 대신 **.ics로 애플 캘린더에 넣어두면**
  캘린더가 하루 전 알림을 대신 띄워줍니다.

고친 뒤에는 반드시 다시 빌드해야 합니다.

```bash
npm install          # esbuild
npm run build:bookmarklet
```

`bookmarklet/dist/` 에 세 개가 나옵니다.

| 파일 | 용도 |
|---|---|
| `loader.txt` | **권장.** 번들을 CDN에서 불러오는 짧은 `javascript:` 문자열 |
| `bookmarklet.txt` | 전체 자립형 버전 (약 19KB) |
| `install.html` | 복사 버튼과 설치 절차가 담긴 페이지. 아이패드에서 이걸 열면 편합니다 |
| `bookmarklet.js` | CDN이 서빙하는 번들. 로더가 이 파일을 가리킵니다 |

로더는 **커밋 SHA로 고정**됩니다. jsDelivr가 슬래시 들어간 브랜치 이름을 제대로 못 끊기 때문이고,
SHA 고정이면 CDN 캐시도 영구적이라 오히려 낫습니다. 그래서 순서가 중요합니다.

```bash
npm run build:bookmarklet          # 번들을 새로 만들고
git add -A && git commit           # 커밋해서 SHA를 확정한 뒤
npm run build:bookmarklet          # 그 SHA로 로더를 다시 생성
```

빌드는 `--ref` 가 가리키는 커밋의 번들과 방금 빌드한 번들을 **비교해서 다르면 실패**합니다.
로더가 낡은 코드를 조용히 가리키는 게 제일 고약한 실패라서 막아뒀습니다.

<br>

## 구조

```
manifest.json               MV3 매니페스트 (권한은 optional_host_permissions로 최소화)
src/
  lib/
    model.js                공통 과제 모델, 날짜 파싱, D-day 계산
    http.js                 세션 쿠키 기반 fetch, Link 헤더 페이지네이션
    storage.js              설정 기본값 + chrome.storage 래퍼
    ics.js                  iCalendar 내보내기
    ui.css                  공유 디자인 토큰 (라이트/다크)
  adapters/
    canvas.js               Canvas 표준 API (planner / todo / 과목별 assignments)
    learningx.js            LearningX 확장 API + Canvas 병합
    custom.js               사용자 지정 엔드포인트 + 필드 매핑
    html.js                 HTML 스크래핑 (offscreen DOMParser)
    index.js                어댑터 레지스트리와 자동 감지
  background/
    service-worker.js       동기화, 알람, 알림, 메시지 라우팅
    capture.js              엔드포인트 탐색 기록
    offscreen.js            서비스워커 대신 HTML을 파싱하는 문서
  content/
    capture-main.js         fetch/XHR 후킹 (MAIN world, 읽기 전용)
    capture-relay.js        MAIN → 백그라운드 중계
  popup/                    툴바 팝업 (임박순 / 오늘·내일 / 전체)
  dashboard/                전체 화면 보드 (통계, 필터, 검색, ics 내보내기)
  options/                  설정 + 엔드포인트 탐색 UI
bookmarklet/
  src/main.js               아이패드용 오버레이 UI (확장의 어댑터를 그대로 import)
  src/install.template.html 설치 페이지 템플릿
  build.mjs                 esbuild 번들 → javascript: URL → install.html 생성
  dist/                     빌드 산출물 (커밋됨)
tests/                      날짜·정렬·어댑터 테스트
docs/DISCOVERY.md           e-Class 엔드포인트 찾는 법
```

### 어댑터 인터페이스

새 LMS를 붙이려면 이것만 구현하면 됩니다.

```js
export const myAdapter = {
  id: 'my-lms',
  label: '사람이 읽는 이름',
  async detect(origin, signal, settings) { /* true면 자동 감지 후보 */ },
  async fetchAll(origin, { signal, settings }) {
    return [ /* normalize()를 거친 과제 배열 */ ];
  },
};
```

`src/adapters/index.js`의 `ADAPTERS`에 추가하면 끝입니다.
결과는 `dedupe()`로 중복 제거되고 `sortByDue()`로 정렬됩니다.

<br>

## 과제와 자료를 왜 나누나

LMS의 "할 일" 목록에는 과제뿐 아니라 **공지와 강의자료도 섞여 나옵니다.** 그런데 공지에는 마감일이
없고 "올라온 날짜"만 있습니다. 이 둘을 구분하지 않으면 지난주에 올라온 강의자료 PPT가
`10일 지남` 이라는 빨간 딱지를 달고 목록 맨 위를 차지합니다.

그래서 모든 항목에 `kind` 를 붙입니다.

| kind | 무엇 | 날짜의 의미 |
|---|---|---|
| `task` | 과제·퀴즈·시험·토론·출석, 그리고 **실제 마감일이 걸린 모든 것** | 마감일 (`dueAt`) |
| `resource` | 공지·강의자료·페이지 등 마감이 없는 것 | 올라온 날짜 (`postedAt`) |

`dueAt` 과 `postedAt` 은 모델에서 **절대 섞이지 않습니다.** 마감일 자리에 올라온 날짜를 채워 넣는
폴백은 두지 않았습니다. D-day 계산, 통계, 알림, 캘린더 내보내기는 전부 `task` 만 봅니다.

<br>

## 알아둘 점

- e-Class에 **로그인되어 있어야** 동기화가 됩니다. 세션이 풀리면 팝업에 안내가 뜹니다.
- 자동 새로고침 주기는 최소 15분입니다 (`chrome.alarms` 제한).
- `learningx` 어댑터의 확장 API 경로(`/learningx/api/v1/...`)는 버전에 따라 다를 수 있어
  **후보를 순서대로 시도**하고 실패하면 조용히 건너뜁니다. Canvas 표준 API만으로도 과제는 다 나옵니다.
  더 정확히 맞추고 싶으면 탐색 기능으로 실제 경로를 확인하세요.
- 과제를 **가져오기만** 합니다. 제출하거나 수정하는 기능은 없습니다.
- 확장과 북마클릿은 **서로 동기화되지 않습니다.** 각자 e-Class에서 직접 읽어오므로 결과는 같지만,
  필터 설정 같은 건 기기별로 따로 놉니다.
