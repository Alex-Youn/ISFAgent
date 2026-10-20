# HttpProbe - 선택형 에이전트 0-3단계 시험 도구

폐쇄망 DB 서버의 JVM(IBM J9 Java 8)에서 `com.sun.net.httpserver`를 쓸 수 있고, 앱 서버에서 그 포트로 접속되는지 확인한다.
에이전트 본체가 아니며 **인증이 없으므로 확인이 끝나면 즉시 종료**한다. 확인 절차와 판정 기준은 메인 저장소
`설계문서/에이전트_선택형_구현체크리스트_2026-10-20.md`의 0-3을 따른다.

## 빌드 (Windows, Java 8 클래스 파일)

```
cd probe
mkdir build dist
..\..\jdk17\bin\javac --release 8 -encoding UTF-8 -d build src\HttpProbe.java src\HttpProbeServer.java
..\..\jdk17\bin\jar cf dist\httpprobe.jar -C build .
```

`dist\httpprobe.jar`(약 6KB, 클래스 파일 버전 52 = Java 8)를 반입한다. 반입 뒤 SHA-256을 대조한다.

## 사용 (DB 서버, `sfo_user`)

```
JAVA=<서버에서 쓸 java 경로>            # 예: dist-aix/java8/bin/java

$JAVA -cp httpprobe.jar HttpProbe --check                         # 1) 클래스 존재·JVM 정보. 마지막 줄 RESULT=OK 면 통과
nohup $JAVA -cp httpprobe.jar HttpProbe 0.0.0.0 18710 > probe.log 2>&1 &   # 2) 시험 서버 기동(임시 포트)
$JAVA -cp httpprobe.jar HttpProbe --get  http://127.0.0.1:18710/probe       # 3) 같은 서버에서 호출
```

## 사용 (앱 서버)

```
$JAVA -cp httpprobe.jar HttpProbe --get  http://<DB서버 IP>:18710/probe
$JAVA -cp httpprobe.jar HttpProbe --post http://<DB서버 IP>:18710/probe abc   # X-Agent-Ts·X-Agent-Sig 헤더와 본문 길이 3 확인
```

`curl`/`wget`이 있으면 그것을 써도 된다. 클라이언트 모드는 앱이 에이전트에 접속하는 방식(`HttpURLConnection`)과 같다.

## 응답

- 성공: `HTTP 200` + JSON(`method`, `xAgentTs`, `xAgentSig`, `bodyLength`, `remote`, `host`, `javaVersion`, `vmName`, `os`, `user`)
- 실패: `FAIL java.net.ConnectException: Connection refused`(서버 미기동·바인드 주소) / `SocketTimeoutException`(방화벽·라우팅) / `HTTP 404`(경로)

## 종료·정리

```
ps -ef | grep HttpProbe          # PID 확인
kill <PID>
rm httpprobe.jar probe.log       # 시험 파일 삭제
```

파일·DB는 건드리지 않는다. 시험 서버는 요청 메서드·시험 헤더·본문 길이와 JVM 정보만 응답한다.
