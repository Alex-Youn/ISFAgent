package com.dbagent.agent.alertlog;

import com.dbagent.agent.AgentException;
import com.dbagent.agent.AgentLog;
import com.dbagent.agent.config.AgentConfig;
import com.dbagent.agent.config.SidConfig;
import com.dbagent.agent.http.Json;
import com.dbagent.agent.path.PathGuard;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 알럿 로그 읽기(1-A5 tail, 1-A6 search, 설계서 3.2·6장). 읽기 전용이고 파일 전체를 읽지 않는다.
 *
 * 응답 의미: 읽었는데 없으면 정상 응답에 빈 목록, 못 읽으면 오류 응답이다("알럿 없음"과 "읽지 못함"을 구분 - 프로젝트 원칙).
 * truncated=true 는 "조건에 맞는 항목이 더 있을 수 있는데 다 보지 못했음"(줄·건수 상한, 읽기 바이트·시간 상한, 응답 크기 상한).
 */
public final class AlertLogService {

    /** 줄 하나·항목 하나가 응답을 키우지 않게 하는 상한. 넘으면 잘라서 표시한다. */
    private static final int MAX_LINE_CHARS = 4000;
    private static final int MAX_ENTRY_CHARS = 20000;
    private static final int MAX_KEYWORD = 100;
    /** 한 요청의 읽기 시간 상한(설계서 3.6). JDK 내장 서버의 응답 시간 상한(maxRspTime 60초)보다 충분히 짧게 둔다. */
    private static final long SCAN_TIME_LIMIT_MS = 20000L;
    /** 줄·항목 하나가 JSON 에서 차지하는 고정 부분({"ts":13자리,"text":""} 와 쉼표)의 넉넉한 어림값. */
    private static final int ENTRY_JSON_OVERHEAD = 40;
    private static final String CUT = "...(cut)";
    private static final Pattern ORA = Pattern.compile("ORA-\\d{5}");
    private static final Pattern SID_ARG = Pattern.compile("[A-Za-z0-9_]{1,30}");

    private final AgentConfig cfg;
    private final PathGuard guard;

    public AlertLogService(AgentConfig cfg, PathGuard guard) {
        this.cfg = cfg;
        this.guard = guard;
    }

    // ---------------------------------------------------------------- tail

    public Map<String, Object> tail(Map<String, String> q) {
        SidConfig sc = sid(q);
        int lines = intArg(q, "lines", 100, 1, cfg.getAlertMaxLines());
        Path file = guard.checkReadableFile(sc.getAlertLog());
        try (BackwardLineReader r = new BackwardLineReader(file, cfg.getAlertMaxScanBytes(), SCAN_TIME_LIMIT_MS)) {
            List<String> rev = new ArrayList<String>();
            long outBytes = 0;
            boolean cutByOutput = false;
            byte[] b;
            while (rev.size() < lines && (b = r.nextLine()) != null) {
                String s = cut(new String(b, cfg.getAlertCharset()), MAX_LINE_CHARS);
                // 응답 크기는 실제 JSON 바이트로 잰다 - 한글은 \\uXXXX(6바이트)로 나가 글자 수로 재면 상한의 6배까지 커진다.
                outBytes += Json.escapedLength(s) + ENTRY_JSON_OVERHEAD;
                if (outBytes > cfg.getOutputMaxBytes()) {
                    cutByOutput = true;
                    break;
                }
                rev.add(s);
            }
            // 더 앞에 줄이 남아 있는지 한 줄 더 읽어 본다
            boolean more = cutByOutput || r.nextLine() != null || r.limitReached();
            Collections.reverse(rev);

            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>(rev.size());
            Long cur = null;        // 창 안에서 가장 가까운 앞쪽 타임스탬프. 창의 첫 타임스탬프 전 줄은 null
            for (String s : rev) {
                Long t = AlertTimestamp.parse(s, cfg.getAlertZone());
                if (t != null) {
                    cur = t;
                }
                out.add(line(cur, s));
            }
            Map<String, Object> res = new LinkedHashMap<String, Object>();
            res.put("lines", out);
            res.put("truncated", more);
            res.put("fileSize", r.fileSize());
            res.put("scannedBytes", r.scannedBytes());
            return res;
        } catch (IOException e) {
            throw ioFailure(sc, e);
        }
    }

    // ---------------------------------------------------------------- search

    public Map<String, Object> search(Map<String, String> q) {
        SidConfig sc = sid(q);
        long from = longArg(q, "from", 0L);
        long to = longArg(q, "to", Long.MAX_VALUE);
        if (from > to) {
            throw AgentException.badRequest("from 이 to 보다 클 수 없습니다.");
        }
        int limit = intArg(q, "limit", 200, 1, cfg.getAlertMaxLines());
        String keyword = q.get("keyword");
        if (keyword != null) {
            keyword = keyword.trim();
            if (keyword.length() > MAX_KEYWORD) {
                throw AgentException.badRequest("keyword 는 " + MAX_KEYWORD + "자 이하여야 합니다.");
            }
            keyword = keyword.isEmpty() ? null : keyword.toLowerCase(Locale.ROOT);
        }
        boolean oraOnly = boolArg(q, "oraOnly");

        Path file = guard.checkReadableFile(sc.getAlertLog());
        try (BackwardLineReader r = new BackwardLineReader(file, cfg.getAlertMaxScanBytes(), SCAN_TIME_LIMIT_MS)) {
            List<Map<String, Object>> found = new ArrayList<Map<String, Object>>();      // 최신 것부터
            // 현재 항목의 본문. 거꾸로 읽으므로 끝쪽 줄이 먼저 들어오고(앞), 머리 줄에 가까운 줄이 뒤에 붙는다.
            // 화면에는 머리 줄부터 MAX_ENTRY_CHARS 까지만 보이므로, 그 분량을 넘으면 끝쪽(앞) 줄을 버린다 - 머리 쪽을 버리면
            // 머리 줄 바로 뒤가 중간 줄로 건너뛰어 보인다(2026-10-21 점검 재현: 6000줄 항목에서 1~1001줄이 표시 없이 빠짐).
            // 분량으로 묶어 두므로 타임스탬프 없는 구간이 길어도(최대 max-scan-mb) 메모리에 다 쌓이지 않는다.
            ArrayDeque<String> body = new ArrayDeque<String>();
            long bodyChars = 0;
            boolean bodyCut = false;
            // 일치 판정은 잘리기 전 줄마다 한다 - 긴 항목 끝쪽의 ORA- 를 잘린 뒤에 찾으면 "없음"으로 잘못 답한다(점검 재현).
            boolean bodyKeyword = false;
            boolean bodyOra = false;
            boolean truncated = false;
            long outBytes = 0;
            byte[] b;
            while ((b = r.nextLine()) != null) {
                String s = new String(b, cfg.getAlertCharset());
                Long ts = AlertTimestamp.parse(s, cfg.getAlertZone());
                if (ts == null) {
                    if (keyword != null && !bodyKeyword && s.toLowerCase(Locale.ROOT).contains(keyword)) {
                        bodyKeyword = true;
                    }
                    if (oraOnly && !bodyOra && ORA.matcher(s).find()) {
                        bodyOra = true;
                    }
                    String kept = cut(s, MAX_LINE_CHARS);
                    body.addLast(kept);
                    bodyChars += kept.length() + 1;
                    while (body.size() > 1 && bodyChars - (body.peekFirst().length() + 1) >= MAX_ENTRY_CHARS) {
                        bodyChars -= body.pollFirst().length() + 1;
                        bodyCut = true;
                    }
                    continue;
                }
                // s 가 항목의 머리(타임스탬프) 줄이다. 파일은 시간순이라 from 보다 이른 항목을 만나면 더 볼 필요가 없다.
                if (ts < from) {
                    break;
                }
                if (ts <= to) {
                    boolean kwOk = keyword == null || bodyKeyword || s.toLowerCase(Locale.ROOT).contains(keyword);
                    boolean oraOk = !oraOnly || bodyOra || ORA.matcher(s).find();
                    if (kwOk && oraOk) {
                        String text = entryText(s, body, bodyCut);
                        long size = Json.escapedLength(text) + ENTRY_JSON_OVERHEAD;
                        if (found.size() >= limit || outBytes + size > cfg.getOutputMaxBytes()) {
                            truncated = true;
                            break;
                        }
                        outBytes += size;
                        found.add(line(ts, text));
                    }
                }
                body.clear();
                bodyChars = 0;
                bodyCut = false;
                bodyKeyword = false;
                bodyOra = false;
            }
            if (r.limitReached()) {
                truncated = true;
            }
            Collections.reverse(found);
            Map<String, Object> res = new LinkedHashMap<String, Object>();
            res.put("entries", found);
            res.put("scannedBytes", r.scannedBytes());
            res.put("truncated", truncated);
            res.put("fileSize", r.fileSize());
            return res;
        } catch (IOException e) {
            throw ioFailure(sc, e);
        }
    }

    // ---------------------------------------------------------------- 도우미

    /** 머리 줄 + 본문(파일 순서). body 는 끝쪽 줄이 앞에 있어 뒤에서부터 붙인다. 잘린 표시는 항상 끝에 붙는다(버린 쪽이 끝쪽이므로). */
    private static String entryText(String head, ArrayDeque<String> body, boolean bodyCut) {
        StringBuilder sb = new StringBuilder(Math.min(MAX_ENTRY_CHARS + 64, head.length() + 64));
        sb.append(head);
        for (Iterator<String> it = body.descendingIterator(); it.hasNext(); ) {
            sb.append('\n').append(it.next());
            if (sb.length() > MAX_ENTRY_CHARS) {
                break;
            }
        }
        String s = cut(sb.toString(), MAX_ENTRY_CHARS);
        return bodyCut && !s.endsWith(CUT) ? s + "\n" + CUT : s;
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + CUT;
    }

    private static Map<String, Object> line(Long ts, String text) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ts", ts);
        m.put("text", text);
        return m;
    }

    private SidConfig sid(Map<String, String> q) {
        String sid = q.get("sid");
        if (sid == null || !SID_ARG.matcher(sid).matches()) {
            throw AgentException.badRequest("sid 가 없거나 형식이 올바르지 않습니다.");
        }
        SidConfig sc = cfg.getSids().get(sid);
        if (sc == null) {
            throw AgentException.notFound("등록되지 않은 sid 입니다.");
        }
        return sc;
    }

    private static int intArg(Map<String, String> q, String name, int def, int min, int max) {
        String v = q.get(name);
        if (v == null || v.isEmpty()) {
            return def;
        }
        try {
            long n = Long.parseLong(v);
            if (n < min || n > max) {
                throw AgentException.badRequest(name + " 는 " + min + " 이상 " + max + " 이하여야 합니다.");
            }
            return (int) n;
        } catch (NumberFormatException e) {
            throw AgentException.badRequest(name + " 는 정수여야 합니다.");
        }
    }

    private static long longArg(Map<String, String> q, String name, long def) {
        String v = q.get(name);
        if (v == null || v.isEmpty()) {
            return def;
        }
        try {
            long n = Long.parseLong(v);
            if (n < 0) {
                throw AgentException.badRequest(name + " 는 0 이상이어야 합니다.");
            }
            return n;
        } catch (NumberFormatException e) {
            throw AgentException.badRequest(name + " 는 정수(epoch ms)여야 합니다.");
        }
    }

    private static boolean boolArg(Map<String, String> q, String name) {
        String v = q.get(name);
        if (v == null || v.isEmpty() || "false".equalsIgnoreCase(v)) {
            return false;
        }
        if ("true".equalsIgnoreCase(v)) {
            return true;
        }
        throw AgentException.badRequest(name + " 는 true 또는 false 여야 합니다.");
    }

    private static AgentException ioFailure(SidConfig sc, IOException e) {
        AgentLog.warn("alert log read failed sid=" + sc.getSid() + " error=" + e);
        return new AgentException(500, "INTERNAL", "알럿 로그를 읽는 중 오류가 발생했습니다.");
    }
}
