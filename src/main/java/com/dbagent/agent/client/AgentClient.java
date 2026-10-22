package com.dbagent.agent.client;

import com.dbagent.agent.auth.Signer;
import com.dbagent.agent.config.AgentConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * 에이전트에 서명된 GET/POST 를 보내는 작은 클라이언트(--get / --health / --post). 이 서버에서 직접 에이전트를 확인하는 용도다:
 * 기동 직후 자기 health 확인(1-A9), 알럿 로그 tail/search 현장 시험, 앱의 접속 방식(HttpURLConnection)과 같은 경로 검증.
 * 서명 계산은 서버 검증과 같은 Signer 를 쓴다.
 */
public final class AgentClient {

    private static final SecureRandom RANDOM = new SecureRandom();

    private AgentClient() {
    }

    /** @return 프로세스 종료 코드: HTTP 200 이면 0, 아니면 1, 접속 불가 2 */
    public static int get(AgentConfig cfg, String pathAndQuery) {
        return send(cfg, "GET", pathAndQuery, new byte[0]);
    }

    /** 서명된 POST. 본문은 form 형식(예: "sid=PATDB1&fileName=a.trc&notBefore=1760000000000"). 종료 코드는 get 과 같다. */
    public static int post(AgentConfig cfg, String pathAndQuery, String formBody) {
        return send(cfg, "POST", pathAndQuery, formBody.getBytes(StandardCharsets.UTF_8));
    }

    private static int send(AgentConfig cfg, String method, String pathAndQuery, byte[] body) {
        if (!pathAndQuery.startsWith("/")) {
            System.err.println("Path must start with / : " + pathAndQuery);
            return 2;
        }
        String host = cfg.getBindAddress();
        if ("0.0.0.0".equals(host)) {
            host = "127.0.0.1";
        }
        int q = pathAndQuery.indexOf('?');
        String path = q < 0 ? pathAndQuery : pathAndQuery.substring(0, q);
        String query = q < 0 ? null : pathAndQuery.substring(q + 1);
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + cfg.getPort() + pathAndQuery).openConnection();
            c.setConnectTimeout(3000);
            // POST(tkprof)는 서버가 제한 시간(agent.tkprof.timeout-seconds)까지 걸릴 수 있어 그보다 길게 기다린다.
            c.setReadTimeout("POST".equals(method) ? (cfg.getTkprofTimeoutSeconds() + 40) * 1000 : 30000);
            c.setRequestMethod(method);
            sign(cfg, c, method, path, query, body);
            if (body.length > 0) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                OutputStream out = c.getOutputStream();
                out.write(body);
                out.close();
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            System.out.println("HTTP " + code);
            System.out.println(in == null ? "" : readAll(in));
            return code == 200 ? 0 : 1;
        } catch (IOException e) {
            System.err.println("Cannot reach the agent at " + host + ":" + cfg.getPort() + " - " + e);
            return 2;
        } catch (IllegalStateException e) {
            // bearer + token-sha256 만 설정한 경우: 스택 트레이스 대신 이유 한 줄(start-agent.sh 의 health 출력에 그대로 보인다)
            System.err.println("Cannot sign the request - " + e.getMessage());
            return 2;
        }
    }

    private static void sign(AgentConfig cfg, HttpURLConnection c, String method, String path, String query, byte[] body) {
        if (AgentConfig.AUTH_BEARER.equals(cfg.getAuthMode())) {
            if (cfg.getToken() == null) {
                throw new IllegalStateException("bearer mode with only agent.token-sha256 cannot send requests (the raw token is unknown)");
            }
            c.setRequestProperty("Authorization", "Bearer " + cfg.getToken());
            return;
        }
        String ts = String.valueOf(System.currentTimeMillis());
        byte[] n = new byte[16];
        RANDOM.nextBytes(n);
        String nonce = Signer.sha256Hex(n).substring(0, 32);
        String sig = Signer.hmacSha256Hex(cfg.getToken(),
                Signer.stringToSign(method, path, query, ts, nonce, Signer.sha256Hex(body)));
        c.setRequestProperty("X-Agent-Ts", ts);
        c.setRequestProperty("X-Agent-Nonce", nonce);
        c.setRequestProperty("X-Agent-Sig", sig);
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
