package com.dbagent.agent.tkprof;

import com.dbagent.agent.AgentException;
import com.dbagent.agent.config.AgentConfig;
import com.dbagent.agent.config.SidConfig;
import com.dbagent.agent.http.Query;
import com.dbagent.agent.path.PathGuard;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * POST /v1/tkprof (2단계, 설계서 3.2). 요청 본문은 application/x-www-form-urlencoded 이다(쿼리 문자열과 같은 규칙) -
 * 인자가 모두 짧은 값이라 JSON 해석기를 새로 만들 필요가 없고, 이미 점검한 Query 를 그대로 쓴다. 본문도 서명에 포함되어 인증된다.
 *
 *   sid         필수. agent.sids 에 있는 이름
 *   fileName    필수. 트레이스 폴더 안의 .trc 파일 <b>이름</b>만 (경로 불가)
 *   notBefore   필수. epoch ms. 이 시각 이후에 만들어졌거나 수정된 파일만 허용 (앱이 트레이스를 켜기 직전 시각)
 *   sort        선택. elapsed(기본)/exeela/fchela/cpu/disk/query - 고정 목록
 *   waits       선택. true(기본)/false
 *   printLimit  선택. 1~500, 없으면 전체
 *   maxDumpBytes 선택. 트레이스 파일 한도(max_dump_file_size 를 바이트로 환산한 값, 앱이 넘김). 파일이 이 값의 95% 이상이면 응답
 *               traceMayBeCut=true. 없거나 0 이면 판정하지 않고 cutChecked=false (2-A4)
 *
 * 순서: 요청 해석 -> 트레이스 파일 확인(TraceFileResolver, 2-A1) -> tkprof 실행(TkprofRunner, 2-A2·2-A3).
 * 오류 코드는 알럿 로그와 같은 규약(400/403/404/413/500)에 TKPROF_UNAVAILABLE(503), BUSY(429), TIMEOUT(504)를 더한다.
 */
public final class TkprofService {

    private static final Pattern SID_ARG = Pattern.compile("[A-Za-z0-9_]{1,30}");
    private static final int MAX_BODY_BYTES = 2048;

    private final AgentConfig cfg;
    private final TraceFileResolver resolver;
    private final TkprofRunner runner;

    public TkprofService(AgentConfig cfg, PathGuard guard, TkprofRunner runner) {
        this.cfg = cfg;
        this.resolver = new TraceFileResolver(cfg, guard);
        this.runner = runner;
    }

    public Map<String, Object> run(byte[] body) {
        if (body == null || body.length == 0) {
            throw AgentException.badRequest("요청 본문이 없습니다.");
        }
        if (body.length > MAX_BODY_BYTES) {
            throw AgentException.badRequest("요청 본문이 너무 깁니다.");
        }
        Map<String, String> p = Query.parse(new String(body, StandardCharsets.UTF_8));
        SidConfig sc = sid(p.get("sid"));
        long notBefore = notBefore(p.get("notBefore"));
        TkprofOptions options = TkprofOptions.parse(p);
        ResolvedTrace trace = resolver.resolve(sc, p.get("fileName"), notBefore);
        return runner.run(sc, trace, options);
    }

    private SidConfig sid(String sid) {
        if (sid == null || !SID_ARG.matcher(sid).matches()) {
            throw AgentException.badRequest("sid 가 없거나 형식이 올바르지 않습니다.");
        }
        SidConfig sc = cfg.getSids().get(sid);
        if (sc == null) {
            throw AgentException.notFound("등록되지 않은 sid 입니다.");
        }
        return sc;
    }

    private long notBefore(String v) {
        if (v == null || v.isEmpty()) {
            throw AgentException.badRequest("notBefore 가 없습니다(epoch ms).");
        }
        long n;
        try {
            n = Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw AgentException.badRequest("notBefore 는 정수(epoch ms)여야 합니다.");
        }
        // 미래 시각은 의미가 없고, 0 이나 아주 오래된 값은 시각 검사를 무력화하므로 양수이고 현재(+시계 오차) 이하여야 한다.
        if (n <= 0 || n > System.currentTimeMillis() + cfg.getClockSkewSeconds() * 1000L) {
            throw AgentException.badRequest("notBefore 가 올바른 시각이 아닙니다.");
        }
        return n;
    }
}
