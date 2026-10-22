package com.dbagent.agent.http;

import com.dbagent.agent.AgentException;
import com.dbagent.agent.AgentLog;
import com.dbagent.agent.alertlog.AlertLogService;
import com.dbagent.agent.auth.RequestAuthenticator;
import com.dbagent.agent.config.AgentConfig;
import com.dbagent.agent.path.PathGuard;
import com.dbagent.agent.tkprof.TkprofRunner;
import com.dbagent.agent.tkprof.TkprofService;
import com.dbagent.agent.tkprof.TraceFindService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JDK 내장 com.sun.net.httpserver 기반 서버(1-A2). 설계서 T1 A안 - 2026-10-21 폐쇄망 IBM J9 1.8.0_503(AIX)에서 기동·접속 확인.
 *
 * 모든 요청은 같은 순서로 처리한다: 본문 읽기(상한) -> 인증 -> 경로 라우팅 -> JSON 응답.
 * 인증을 라우팅보다 먼저 하므로 인증 없는 호출자는 어떤 경로가 있는지도 알 수 없다(없는 경로도 401).
 * 메타데이터만 로그에 남긴다(메서드·경로·상태·소요 시간·클라이언트 IP). 쿼리 값과 응답 내용은 남기지 않는다.
 */
public final class AgentHttpServer {

    private static final int MAX_BODY_BYTES = 64 * 1024;

    static {
        // 느리게 보내는 연결(slowloris)이 스레드 4개를 붙잡지 못하게 요청·응답 시간 상한을 둔다. JDK 내장 서버가 읽는 시스템 속성이며,
        // HttpServer 클래스가 처음 쓰이기 전에 설정해야 한다. 이 속성을 모르는 JVM 에서는 무시되어 동작에 영향이 없다.
        setIfAbsent("sun.net.httpserver.maxReqTime", "30");
        // maxRspTime 은 tkprof 제한 시간을 알아야 하므로 설정을 받는 생성자에서 정한다.
    }

    private final AgentConfig cfg;
    private final RequestAuthenticator auth;
    private final HealthService health;
    private final AlertLogService alertLog;
    private final TkprofRunner tkprofRunner;
    private final TkprofService tkprof;
    private final TraceFindService traceFinder;
    private HttpServer server;
    private ExecutorService executor;

    public AgentHttpServer(AgentConfig cfg) {
        this.cfg = cfg;
        // JDK 내장 서버는 요청을 받은 뒤 응답을 다 쓸 때까지의 시간에도 상한(maxRspTime)을 둔다. tkprof 가 제한 시간까지 돌 수 있으므로
        // 그보다 길어야 504 응답을 보낼 수 있다(아니면 연결이 먼저 끊긴다). HttpServer 클래스가 처음 쓰이기 전에 설정해야 한다.
        setIfAbsent("sun.net.httpserver.maxRspTime", String.valueOf(Math.max(60, cfg.getTkprofTimeoutSeconds() + 30)));
        PathGuard guard = new PathGuard(cfg.getAllowedRoots());
        this.auth = new RequestAuthenticator(cfg);
        this.tkprofRunner = new TkprofRunner(cfg);
        this.health = new HealthService(cfg, guard, tkprofRunner);
        this.alertLog = new AlertLogService(cfg, guard);
        this.tkprof = new TkprofService(cfg, guard, tkprofRunner);
        this.traceFinder = new TraceFindService(cfg, guard);
    }

    public void start() throws IOException {
        tkprofRunner.cleanWorkDir();
        InetSocketAddress addr = new InetSocketAddress(InetAddress.getByName(cfg.getBindAddress()), cfg.getPort());
        server = HttpServer.create(addr, 50);
        executor = Executors.newFixedThreadPool(cfg.getHttpThreads(), new NamedDaemonFactory());
        server.setExecutor(executor);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                AgentHttpServer.this.handle(ex);
            }
        });
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
        }
        if (executor != null) {
            executor.shutdown();
        }
    }

    // ---------------------------------------------------------------- 요청 처리

    private void handle(HttpExchange ex) throws IOException {
        long t0 = System.nanoTime();
        int status = 500;
        String code = "-";
        String method = ex.getRequestMethod();
        URI uri = ex.getRequestURI();
        String ip = ex.getRemoteAddress().getAddress().getHostAddress();
        try {
            byte[] body = readBody(ex.getRequestBody());
            auth.authenticate(ip, method, uri.getRawPath(), uri.getRawQuery(),
                    ex.getRequestHeaders().getFirst("X-Agent-Ts"), ex.getRequestHeaders().getFirst("X-Agent-Nonce"),
                    ex.getRequestHeaders().getFirst("X-Agent-Sig"), ex.getRequestHeaders().getFirst("Authorization"), body);
            Object result = route(method, uri.getPath(), Query.parse(uri.getRawQuery()), body);
            status = 200;
            send(ex, 200, result);
        } catch (AgentException e) {
            status = e.getStatus();
            code = e.getCode();
            sendIfNotStarted(ex, status, error(code, e.getMessage()));
        } catch (IOException e) {
            // 본문 읽기·응답 쓰기 중 연결이 끊긴 경우(클라이언트 종료, 서버 시간 상한). 다시 응답할 곳이 없으므로 기록만 한다.
            code = "IO";
            AgentLog.info("connection error " + AgentLog.safe(method) + " " + AgentLog.safe(uri.getRawPath()) + " client=" + ip
                    + " error=" + e.getClass().getSimpleName());
        } catch (Throwable t) {
            // 예상 밖 오류는 상세를 응답에 싣지 않고 로그에만 남긴다
            AgentLog.error("unexpected error handling " + AgentLog.safe(method) + " " + AgentLog.safe(uri.getRawPath()), t);
            status = 500;
            code = "INTERNAL";
            sendIfNotStarted(ex, 500, error(code, "서버 내부 오류가 발생했습니다."));
        } finally {
            ex.close();
            AgentLog.info("req " + AgentLog.safe(method) + " " + AgentLog.safe(uri.getRawPath()) + " status=" + status
                    + (code.equals("-") ? "" : " code=" + code) + " ms=" + (System.nanoTime() - t0) / 1000000L + " client=" + ip);
        }
    }

    /**
     * 응답 헤더를 이미 보냈으면(본문 쓰는 중 실패) 두 번째 응답을 시도하지 않는다 - 시도하면 "headers already sent" IOException 이
     * 핸들러 밖으로 나가 원래 오류를 덮는다. 오류 응답 자체가 연결 끊김으로 실패해도 마찬가지로 조용히 넘긴다(요청 로그는 finally 에서 남는다).
     */
    private static void sendIfNotStarted(HttpExchange ex, int status, Object payload) {
        if (ex.getResponseCode() != -1) {
            return;
        }
        try {
            send(ex, status, payload);
        } catch (IOException ignored) {
            // 클라이언트가 이미 끊음
        }
    }

    private Object route(String method, String path, Map<String, String> q, byte[] body) {
        if ("/v1/tkprof".equals(path)) {
            if (!"POST".equals(method)) {
                throw new AgentException(405, "METHOD_NOT_ALLOWED", "POST 만 지원합니다.");
            }
            return tkprof.run(body);
        }
        boolean health = "/v1/health".equals(path);
        boolean tail = "/v1/alert-log/tail".equals(path);
        boolean search = "/v1/alert-log/search".equals(path);
        boolean traceFind = "/v1/trace/find".equals(path);
        if (!health && !tail && !search && !traceFind) {
            throw AgentException.notFound("없는 경로입니다.");
        }
        if (!"GET".equals(method)) {
            throw new AgentException(405, "METHOD_NOT_ALLOWED", "GET 만 지원합니다.");
        }
        if (health) {
            return this.health.health();
        }
        if (traceFind) {
            return traceFinder.find(q);
        }
        return tail ? alertLog.tail(q) : alertLog.search(q);
    }

    private static byte[] readBody(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > MAX_BODY_BYTES) {
                throw AgentException.tooLarge("요청 본문이 너무 큽니다.");
            }
        }
        return bos.toByteArray();
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("error", code);
        m.put("message", message);
        return m;
    }

    private static void send(HttpExchange ex, int status, Object payload) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if ("HEAD".equals(ex.getRequestMethod())) {
            // HEAD 에 길이를 주고 본문을 쓰면 JDK 8 내장 서버는 쓰기에서 IOException 을 낸다(JDK 17 은 경고만). 본문 없이 응답한다.
            ex.sendResponseHeaders(status, -1);
            return;
        }
        // 길이를 먼저 세고(같은 직렬화 코드) 스트림으로 바로 쓴다 - 응답 전체를 문자열·바이트 배열로 복사하지 않기 위해서다.
        long len = Json.length(payload);
        ex.sendResponseHeaders(status, len);
        Writer w = new BufferedWriter(new OutputStreamWriter(ex.getResponseBody(), StandardCharsets.US_ASCII), 16 * 1024);
        try {
            Json.write(payload, w);     // 순수 ASCII(나머지는 \\uXXXX)라 문자 수 = 바이트 수 = len
        } finally {
            w.close();
        }
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    private static final class NamedDaemonFactory implements ThreadFactory {
        private final AtomicInteger n = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "agent-http-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
