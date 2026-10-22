package com.dbagent.agent.http;

import com.dbagent.agent.AgentMain;
import com.dbagent.agent.config.AgentConfig;
import com.dbagent.agent.config.SidConfig;
import com.dbagent.agent.path.PathGuard;
import com.dbagent.agent.tkprof.TkprofRunner;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /v1/health (1-A4). 앱이 "연결 시험"과 MISMATCH 판정(다른 서버의 에이전트인지)에 쓴다.
 * 경로·토큰 같은 설정 값은 내보내지 않고 가능 여부(boolean)만 알린다. 예외: 트레이스 폴더 실경로(traceDirs, D8)는 앱의 사전 대조에 필요해 알린다.
 */
final class HealthService {

    static final int API_VERSION = 1;

    private final AgentConfig cfg;
    private final PathGuard guard;
    private final TkprofRunner tkprof;
    private final String hostName;

    HealthService(AgentConfig cfg, PathGuard guard, TkprofRunner tkprof) {
        this.cfg = cfg;
        this.guard = guard;
        this.tkprof = tkprof;
        this.hostName = resolveHostName();
    }

    Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("agentVersion", AgentMain.version());
        m.put("apiVersion", API_VERSION);
        m.put("hostName", hostName);
        m.put("osName", System.getProperty("os.name"));
        m.put("javaVersion", System.getProperty("java.version"));
        m.put("timeZone", ZoneId.systemDefault().getId());
        m.put("alertLogTimeZone", cfg.getAlertZone().getId());
        List<String> sids = new ArrayList<String>(cfg.getSids().keySet());
        m.put("sids", sids);
        m.put("tkprofOk", tkprof.isAvailable());
        Map<String, Object> alertOk = new LinkedHashMap<String, Object>();
        for (SidConfig sc : cfg.getSids().values()) {
            alertOk.put(sc.getSid(), guard.isReadable(sc.getAlertLog()));
        }
        m.put("alertLogOk", alertOk);
        // D8(2026-10-22): 앱이 트레이스를 켜기 전에 v$diag_info 'Diag Trace' 와 이 폴더를 대조한다(300초 SQL 뒤 404 방지).
        // 설정 경로는 계속 숨기되 트레이스 폴더의 실경로만 서명된 요청(인증된 호출자)에게 알린다. 폴더를 못 열면 null.
        Map<String, Object> traceDirs = new LinkedHashMap<String, Object>();
        for (SidConfig sc : cfg.getSids().values()) {
            traceDirs.put(sc.getSid(), realTraceDir(sc));
        }
        m.put("traceDirs", traceDirs);
        m.put("tlsEnabled", false);
        m.put("busy", tkprof.isBusy());     // tkprof 동시 실행 자리가 모두 찼다(2-A3)
        return m;
    }

    private static String realTraceDir(SidConfig sc) {
        try {
            Path dir = sc.getEffectiveTraceDir();
            return dir == null ? null : dir.toRealPath().toString();
        } catch (IOException e) {
            return null;
        }
    }

    private static String resolveHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            String env = System.getenv("HOSTNAME");
            return env != null ? env : "unknown";
        }
    }
}
