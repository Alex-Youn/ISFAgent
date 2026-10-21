# ISFAgent (dbagent-agent)

DB InsightFlow의 **선택형 에이전트**. DB 서버에 설치해 알럿 로그와 tkprof 결과를 **읽기 전용**으로 제공한다.
앱(`DBAgent-Java` / `DBAgent-Java-AIX`)이 요청할 때만 응답하는 pull 방식이고, 에이전트는 상태·DB 접속이 없다.

- 설계: 메인 저장소 `설계문서/에이전트_선택형_설계_2026-10-20.md`
- 구현 단계·체크리스트: 메인 저장소 `설계문서/에이전트_선택형_구현체크리스트_2026-10-20.md`
- 이 저장소는 메인 `DBAgent-Java` 폴더 안에 있지만 메인 `.gitignore`로 제외된 **독립 git 저장소**(`Alex-Youn/ISFAgent`)다. AIX 저장소에는 복사하지 않는다.

## 현재 상태

체크리스트 1-A(에이전트 MVP + 알럿 로그) 구현 완료, 2단계(tkprof)는 아직이다.

| 엔드포인트 | 내용 |
|---|---|
| `GET /v1/health` | 버전·호스트·OS·시간대·sids·`alertLogOk{sid}`·`tkprofOk` |
| `GET /v1/alert-log/tail?sid=&lines=` | 파일 끝에서 거꾸로 읽는 최근 N줄 |
| `GET /v1/alert-log/search?sid=&from=&to=&keyword=&oraOnly=&limit=` | 시간 범위 + 리터럴 키워드 + `ORA-\d{5}` 필터 |

- 인증: HMAC(`X-Agent-Ts`·`X-Agent-Nonce`·`X-Agent-Sig`), 시각 +-60초, nonce 재사용 거부. `bearer` 모드도 있다(시험용).
- 경로 인자 없음(`sid` -> 설정 경로), 허용 루트 실경로 대조, 심볼릭 링크 거부.
- 시험 결과: 로컬(Windows, Java 17 --release 8) 31개 방어·인자 시험 통과, Linux Java 8(1.8.0_201)에서 실제 알럿 로그·심볼릭 링크·시작/정지 스크립트(ksh) 확인. 폐쇄망 AIX(IBM J9) 실행은 반입 후 확인.

## 실행

```
# 서버 (AIX: dist/ 의 start-agent.sh 사용)
java -jar dbagent-agent.jar --config agent.properties

# 같은 서버에서 직접 확인 (서명된 GET, 앱의 접속 방식과 같음)
java -jar dbagent-agent.jar --config agent.properties --health
java -jar dbagent-agent.jar --config agent.properties --get "/v1/alert-log/tail?sid=PATDB1&lines=20"
```

## 반입 파일 (`dist/`)

| 파일 | 설명 |
|---|---|
| `start-agent.sh` / `stop-agent.sh` | ksh88 호환 문법. `sfo_user`만 실행(root 거부), 번들 `java8/` 우선, 기동 후 자기 health 확인 |
| `agent.properties.sample` | 모든 키 설명. 필수 키는 주석 처리돼 있어 채우기 전에는 기동하지 않는다 |
| (빌드 산출물) `target/dbagent-agent.jar` | 위 파일들과 같은 폴더에 둔다. `dist/` 모음과 `/appbuild` 연동은 체크리스트 1-D3에서 정한다 |

- 스크립트·sample은 **ASCII + LF**만 쓴다(`.gitattributes`가 LF를 강제). AIX 터미널은 EUC-KR이라 UTF-8 한글이 깨지고, CR이 있으면 ksh가 실패한다.

## 결정 사항 (2026-10-21 오케스트레이터)

| 항목 | 내용 |
|---|---|
| 언어 | **Java 8** (소스·클래스 모두). 번들 `jdk17`로 빌드하되 `--release 8` |
| 실행 JVM | 앱 번들 `java8/`(IBM J9 1.8.0_503, AIX) - 폐쇄망 시험(0-3) 통과 |
| HTTP 서버 | JDK 내장 `com.sun.net.httpserver` (외부 의존성 없음, 단일 jar) |
| 대상 OS | AIX 7.1~7.2, 차후 Linux 확장 |
| 스크립트 | ksh (`start-agent.sh` / `stop-agent.sh`는 1-A9) |
| 로그·콘솔 | **영문** (AIX 로케일에서 한글이 `?`로 깨짐) |

## 구조

```
ISFAgent/
  pom.xml                       자체 pom (메인·AIX pom과 무관)
  src/main/java/com/dbagent/agent/
    AgentMain.java              진입점 (서버 / --health / --get)
    AgentException.java AgentLog.java
    config/                     agent.properties 로드·검증
    auth/                       HMAC 서명(Signer)·요청 인증
    http/                       JDK 내장 HttpServer, JSON, 쿼리, health
    alertlog/                   거꾸로 줄 읽기, 타임스탬프 해석, tail/search
    path/                       경로 방어
    client/                     --health / --get 클라이언트
    tkprof/                     2단계 (package-info.java만 있음)
  dist/                         서버에 반입하는 스크립트·sample
  probe/                        0-3 시험 도구 (HttpProbe). 에이전트 본체가 아니다
```

## 빌드

메인 프로젝트의 번들 JDK·Maven을 쓴다(Windows).

```
set JAVA_HOME=C:\AI-Projects\DBAgent-Java\jdk17
C:\AI-Projects\DBAgent-Java\maven39\bin\mvn.cmd clean package
```

산출물: `target/dbagent-agent.jar` (`java -jar dbagent-agent.jar --version`).
`target/`은 커밋하지 않는다. 반입용 `dist/` 모음과 `/appbuild` 연동 여부는 체크리스트 1-D3에서 정한다.

## 코딩 원칙

- Java 8 문법만: `var`·`record`·switch 식·텍스트 블록·`List.of` 등 Java 9+ API 금지 (`--release 8`이 컴파일에서 막는다).
- 주석은 한국어로 "왜"를 쓴다. 결정·실측은 날짜와 출처를 남긴다.
- 에이전트는 읽기 전용: 파일 읽기와 tkprof 출력용 자기 작업 디렉터리 쓰기만 한다. 경로 인자를 받지 않는다.
- 조회 실패를 빈 결과로 내리지 않는다("읽었는데 없음"과 "못 읽음"을 구분).
