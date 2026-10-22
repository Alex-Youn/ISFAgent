package com.dbagent.agent.tkprof;

import com.dbagent.agent.AgentException;
import com.dbagent.agent.AgentLog;
import com.dbagent.agent.config.AgentConfig;
import com.dbagent.agent.config.SidConfig;
import com.dbagent.agent.path.PathGuard;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * GET /v1/trace/find?sid=&identifier= (2-A5, 설계서 3.2). 앱이 v$diag_info·v$process.tracefile 에서 트레이스 파일 이름을 얻지 못할 때의
 * 보조 경로다: tracefile_identifier 를 걸고 만든 트레이스(Oracle 이 &lt;SID&gt;_ora_&lt;spid&gt;_&lt;identifier&gt;.trc 로 이름 붙임)를 SID 의 트레이스 폴더에서 찾는다.
 *
 * 경로는 받지 않는다 - 식별자(영문·숫자·밑줄 30자 이하)로 폴더 안의 <b>이름만</b> 비교하고, 후보 파일은 tkprof 와 같은 검사(PathGuard: 링크·일반 파일·
 * 허용 루트·읽기, 실경로의 부모가 트레이스 폴더)를 통과한 것만 돌려준다. 응답은 수정 시각이 최근인 순 최대 5개의 파일 <b>이름</b>이며,
 * 이후 tkprof 요청의 notBefore 시각 검사는 그대로 적용된다. 폴더 목록은 이름만 훑고(파일 내용·속성은 일치한 것만 읽음), 훑는 개수에 상한을 둔다.
 */
public final class TraceFindService {

    private static final Pattern SID_ARG = Pattern.compile("[A-Za-z0-9_]{1,30}");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]{1,30}");
    private static final int MAX_RESULTS = 5;
    /** 트레이스 폴더가 수십만 개로 커져 있어도 요청 한 건이 오래 걸리지 않게 하는 이름 훑기 상한. */
    private static final int MAX_SCAN = 200000;

    private final AgentConfig cfg;
    private final PathGuard guard;

    public TraceFindService(AgentConfig cfg, PathGuard guard) {
        this.cfg = cfg;
        this.guard = guard;
    }

    public Map<String, Object> find(Map<String, String> q) {
        String sid = q.get("sid");
        if (sid == null || !SID_ARG.matcher(sid).matches()) {
            throw AgentException.badRequest("sid 가 없거나 형식이 올바르지 않습니다.");
        }
        SidConfig sc = cfg.getSids().get(sid);
        if (sc == null) {
            throw AgentException.notFound("등록되지 않은 sid 입니다.");
        }
        String identifier = q.get("identifier");
        if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
            throw AgentException.badRequest("identifier 가 없거나 형식이 올바르지 않습니다(영문·숫자·밑줄 30자 이하).");
        }
        Path dir = sc.getEffectiveTraceDir();
        if (dir == null) {
            throw AgentException.notFound("트레이스 폴더를 알 수 없습니다.");
        }
        final String suffix = "_" + identifier + ".trc";
        final Path realDir;
        try {
            realDir = dir.toRealPath();
        } catch (IOException e) {
            throw AgentException.notFound("트레이스 폴더를 열 수 없습니다.");
        }

        List<Found> found = new ArrayList<Found>();
        boolean scanLimited = false;
        int scanned = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(realDir)) {
            for (Path p : ds) {
                if (++scanned > MAX_SCAN) {
                    scanLimited = true;
                    break;
                }
                String name = p.getFileName().toString();
                if (!name.endsWith(suffix)) {
                    continue;
                }
                try {
                    Path real = guard.checkReadableFile(p);
                    if (real.getParent() != null && real.getParent().equals(realDir)) {
                        found.add(new Found(name, Files.getLastModifiedTime(real).toMillis()));
                    }
                } catch (AgentException skip) {
                    // 링크·허용 루트 밖·읽기 불가 파일은 후보에서 뺀다(오류로 알리지 않는다)
                }
            }
        } catch (IOException e) {
            AgentLog.warn("trace find failed sid=" + sid + " error=" + e);
            throw new AgentException(500, "INTERNAL", "트레이스 폴더를 읽는 중 오류가 발생했습니다.");
        }

        Collections.sort(found, new Comparator<Found>() {
            @Override
            public int compare(Found a, Found b) {
                return a.modifiedMs < b.modifiedMs ? 1 : (a.modifiedMs > b.modifiedMs ? -1 : a.name.compareTo(b.name));
            }
        });
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < found.size() && i < MAX_RESULTS; i++) {
            names.add(found.get(i).name);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("fileNames", names);
        m.put("scanLimited", scanLimited);
        return m;
    }

    private static final class Found {
        final String name;
        final long modifiedMs;

        Found(String name, long modifiedMs) {
            this.name = name;
            this.modifiedMs = modifiedMs;
        }
    }
}
