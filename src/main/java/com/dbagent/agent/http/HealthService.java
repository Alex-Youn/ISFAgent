package com.dbagent.agent.http;

import com.dbagent.agent.AgentMain;
import com.dbagent.agent.config.AgentConfig;
import com.dbagent.agent.config.SidConfig;
import com.dbagent.agent.path.PathGuard;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /v1/health (1-A4). 앱이 "연결 시험"과 MISMATCH 판정(다른 서버의 에이전트인지)에 쓴다.
 * 경로·토큰 같은 설정 값은 내보내지 않고 가능 여부(boolean)만 알린다.
 */
final class HealthService {

    static final int API_VERSION = 1;

    private final AgentConfig cfg;
    private final PathGuard guard;
    private final String hostName;

    HealthService(AgentConfig cfg, PathGuard guard) {
        this.cfg = cfg;
        this.guard = guard;
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
        m.put("tkprofOk", tkprofOk());
        Map<String, Object> alertOk = new LinkedHashMap<String, Object>();
        for (SidConfig sc : cfg.getSids().values()) {
            alertOk.put(sc.getSid(), guard.isReadable(sc.getAlertLog()));
        }
        m.put("alertLogOk", alertOk);
        m.put("tlsEnabled", false);
        m.put("busy", false);       // tkprof 동시 1건 제한은 2단계에서 연결한다
        return m;
    }

    private boolean tkprofOk() {
        Path p = cfg.getTkprofPath();
        return p != null && Files.isRegularFile(p) && Files.isExecutable(p);
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
