# 과제 한눈에 — 중앙대 e-Class

중앙대 e-Class(`eclass3.cau.ac.kr`)에 흩어져 있는 모든 과목의 과제 마감일을,
**한 화면에 D-day 순으로** 모아주는 크롬 확장 프로그램. 개인용.

e-Class(LearningX)는 과목별로 들어가야 과제가 보이기 때문에, 수강 과목이 6개면
마감일 하나 확인하는 데 클릭을 스무 번쯤 해야 합니다. 이 확장은 그걸 한 화면으로 줄입니다.

<br>

## 무엇을 하나

- **통합 마감 보드** — 모든 과목의 과제·퀴즈·토론을 마감 빠른 순으로 한 목록에
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
docs/DISCOVERY.md           우리 학교 엔드포인트 찾는 법
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

## 알아둘 점

- e-Class에 **로그인되어 있어야** 동기화가 됩니다. 세션이 풀리면 팝업에 안내가 뜹니다.
- 자동 새로고침 주기는 최소 15분입니다 (`chrome.alarms` 제한).
- `learningx` 어댑터의 확장 API 경로(`/learningx/api/v1/...`)는 버전에 따라 다를 수 있어
  **후보를 순서대로 시도**하고 실패하면 조용히 건너뜁니다. Canvas 표준 API만으로도 과제는 다 나옵니다.
  더 정확히 맞추고 싶으면 탐색 기능으로 실제 경로를 확인하세요.
- 과제를 **가져오기만** 합니다. 제출하거나 수정하는 기능은 없습니다.
