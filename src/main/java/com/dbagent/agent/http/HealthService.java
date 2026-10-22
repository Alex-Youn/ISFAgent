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
        Map<String, Object> traceDirsConfigured = new LinkedHashMap<String, Object>();
        for (SidConfig sc : cfg.getSids().values()) {
            String real = realTraceDir(sc);
            traceDirs.put(sc.getSid(), real);
            traceDirsConfigured.put(sc.getSid(), real == null ? null : configuredTraceDir(sc));
        }
        m.put("traceDirs", traceDirs);
        // 링크를 풀기 전의 경로(2026-10-22 앱 점검): v$diag_info 'Diag Trace' 는 심볼릭 링크를 풀지 않은 경로를 주므로 /u01 이 링크인 AIX에서는
        // 실경로와 글자가 달라 앱의 사전 대조가 늘 실패한다. 앱은 둘 중 하나라도 맞으면 통과시킨다(같은 폴더를 가리키는 두 표기).
        m.put("traceDirsConfigured", traceDirsConfigured);
        m.put("tlsEnabled", false);
        m.put("busy", tkprof.isBusy());     // tkprof 동시 실행 자리가 모두 찼다(2-A3)
        return m;
    }

    private static String configuredTraceDir(SidConfig sc) {
        Path dir = sc.getEffectiveTraceDir();
        return dir == null ? null : dir.toAbsolutePath().normalize().toString();
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
