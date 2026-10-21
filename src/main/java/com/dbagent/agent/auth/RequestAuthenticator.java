package com.dbagent.agent.auth;

import com.dbagent.agent.AgentException;
import com.dbagent.agent.AgentLog;
import com.dbagent.agent.config.AgentConfig;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/**
 * 요청 인증(1-A3, 설계서 3.3). 모든 실패는 호출자에게 똑같은 401 AUTH_FAILED 로 보이고, 이유는 서버 로그에만 남는다.
 *
 * hmac: 시각 +-clock-skew 초, 서명 일치(상수 시간 비교), nonce 재사용 거부(서명이 맞은 요청의 nonce만 기억 - 틀린 서명으로 nonce 를 소진시킬 수 없다).
 * bearer: Authorization: Bearer 토큰의 SHA-256 을 설정의 해시와 비교.
 * 선택: allowed-clients(앱 서버 IP), 같은 IP 에서 연속 실패하면 응답을 늦춘다(최대 1초).
 */
public final class RequestAuthenticator {

    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_\\-]{8,64}");
    private static final int NONCE_CACHE_MAX = 50000;
    private static final int FAIL_TRACK_MAX = 10000;
    private static final int FAIL_FREE_ATTEMPTS = 4;
    private static final long FAIL_WINDOW_MS = 60000L;

    private final AgentConfig cfg;
    private final boolean hmac;
    private final long skewMs;
    private final long nonceTtlMs;
    private final Set<String> allowedClients;

    private final Map<String, Long> nonces = new ConcurrentHashMap<String, Long>();
    private final Map<String, long[]> failures = new ConcurrentHashMap<String, long[]>();   // ip -> {연속 실패 수, 마지막 실패 시각}
    private final Semaphore sleeper = new Semaphore(1);
    private volatile long lastPurge;

    public RequestAuthenticator(AgentConfig cfg) {
        this.cfg = cfg;
        this.hmac = AgentConfig.AUTH_HMAC.equals(cfg.getAuthMode());
        this.skewMs = cfg.getClockSkewSeconds() * 1000L;
        // 서명이 유효한 시간 창(2 x skew)보다 오래 기억해야 같은 요청을 다시 보내는 공격을 막는다.
        this.nonceTtlMs = Math.max(120000L, 2 * skewMs + 5000L);
        this.allowedClients = cfg.getAllowedClients();
    }

    /**
     * @param path  요청 URI 의 raw 경로
     * @param query 요청 URI 의 raw 쿼리(없으면 null)
     * @throws AgentException 인증 실패(401)
     */
    public void authenticate(String clientIp, String method, String path, String query,
                             String ts, String nonce, String sig, String authorization, byte[] body) {
        long now = System.currentTimeMillis();
        purge(now);
        // 허용 IP 가 설정돼 있으면 그 밖의 주소는 지연 없이 바로 거절한다 - 지연(sleep)이 작업 스레드(기본 4개)를 붙잡는 통로가 되지 않게.
        if (!allowedClients.isEmpty() && !allowedClients.contains(clientIp)) {
            AgentLog.warn("auth failed client=" + clientIp + " reason=client_not_allowed path=" + AgentLog.safe(path));
            throw AgentException.authFailed();
        }
        if (!throttle(clientIp, now)) {
            AgentLog.warn("auth failed client=" + clientIp + " reason=throttled path=" + AgentLog.safe(path));
            throw AgentException.authFailed();
        }

        String reason = check(clientIp, now, method, path, query, ts, nonce, sig, authorization, body);
        if (reason != null) {
            recordFailure(clientIp, now);
            AgentLog.warn("auth failed client=" + clientIp + " reason=" + reason + " path=" + AgentLog.safe(path));
            throw AgentException.authFailed();
        }
        failures.remove(clientIp);
    }

    /** @return 실패 이유(로그용), 성공이면 null */
    private String check(String clientIp, long now, String method, String path, String query,
                         String ts, String nonce, String sig, String authorization, byte[] body) {
        if (!hmac) {
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                return "missing_bearer";
            }
            String presented = Signer.sha256Hex(authorization.substring(7).trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Signer.constantTimeEquals(presented, cfg.getTokenSha256()) ? null : "bad_token";
        }
        if (ts == null || nonce == null || sig == null) {
            return "missing_headers";
        }
        if (!NONCE.matcher(nonce).matches()) {
            return "bad_nonce_format";
        }
        long tsMs;
        try {
            tsMs = Long.parseLong(ts.trim());
        } catch (NumberFormatException e) {
            return "bad_timestamp_format";
        }
        // Math.abs(now - tsMs) 는 tsMs = now + Long.MIN_VALUE 일 때 넘쳐서 음수(통과)가 된다 - 뺄셈 없이 범위로 비교한다.
        if (tsMs < now - skewMs || tsMs > now + skewMs) {
            return "stale_timestamp(skew_ms=" + (now - tsMs) + ")";     // 시계 차이 진단용(값이 터무니없으면 넘친 값일 수 있음)
        }
        String expected = Signer.hmacSha256Hex(cfg.getToken(),
                Signer.stringToSign(method, path, query, ts.trim(), nonce, Signer.sha256Hex(body)));
        if (!Signer.constantTimeEquals(expected, sig.trim().toLowerCase(Locale.ROOT))) {
            return "bad_signature";
        }
        // 서명이 맞은 요청만 nonce 를 기억한다. putIfAbsent 로 동시에 같은 nonce 가 와도 하나만 통과한다.
        if (nonces.size() >= NONCE_CACHE_MAX) {
            purgeNonces(now);
            if (nonces.size() >= NONCE_CACHE_MAX) {
                return "nonce_cache_full";
            }
        }
        if (nonces.putIfAbsent(nonce, now) != null) {
            return "nonce_replay";
        }
        return null;
    }

    /**
     * 같은 IP 에서 연속 실패가 쌓였으면 응답을 늦춘다(무차별 대입 완화). 스레드 풀을 오래 붙잡지 않도록 최대 1초.
     * 지연 중인 스레드는 한 번에 하나만 둔다: allowed-clients 를 비워 둔 채 한 IP 가 연결을 쏟아 넣으면 요청마다 1초씩 작업 스레드
     * (기본 4개)가 모두 잠들고 큐가 밀려 앱의 정상 요청이 몇 분씩 늦어진다(2026-10-21 점검). 이미 하나가 지연 중이면 서명을 보지 않고 바로 거절한다.
     *
     * @return false 면 검사 없이 거절
     */
    private boolean throttle(String ip, long now) {
        long[] f = failures.get(ip);
        if (f == null || now - f[1] > FAIL_WINDOW_MS || f[0] <= FAIL_FREE_ATTEMPTS) {
            return true;
        }
        if (!sleeper.tryAcquire()) {
            return false;
        }
        try {
            Thread.sleep(Math.min(1000L, 200L * (f[0] - FAIL_FREE_ATTEMPTS)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            sleeper.release();
        }
        return true;
    }

    private void recordFailure(String ip, long now) {
        if (failures.size() >= FAIL_TRACK_MAX && !failures.containsKey(ip)) {
            return;
        }
        long[] f = failures.get(ip);
        if (f == null || now - f[1] > FAIL_WINDOW_MS) {
            failures.put(ip, new long[]{1, now});
        } else {
            f[0]++;
            f[1] = now;
        }
    }

    private void purge(long now) {
        if (now - lastPurge < 10000L) {
            return;
        }
        lastPurge = now;
        purgeNonces(now);
        for (Iterator<Map.Entry<String, long[]>> it = failures.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue()[1] > FAIL_WINDOW_MS) {
                it.remove();
            }
        }
    }

    private void purgeNonces(long now) {
        for (Iterator<Map.Entry<String, Long>> it = nonces.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue() > nonceTtlMs) {
                it.remove();
            }
        }
    }
}
