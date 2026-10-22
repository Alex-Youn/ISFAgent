package com.dbagent.agent.tkprof;

import com.dbagent.agent.AgentException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * tkprof 에 넘길 옵션(2-A2). 호출자의 값은 그대로 명령줄에 들어가지 않는다 - sort 는 아래 열거값만 고정 문자열로 바꾸고,
 * waits 는 true/false, print 는 1~500 정수만 받는다. explain=·record=·insert=·table= 은 어떤 경로로도 만들 수 없다
 * (DB 계정·비밀번호가 명령줄에 노출되거나 DB 에서 SQL 이 실행될 수 있는 인자, 설계서 3.5).
 */
final class TkprofOptions {

    static final String DEFAULT_SORT = "elapsed";
    private static final int MAX_PRINT = 500;
    private static final Map<String, String> SORTS;

    static {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("elapsed", "prsela,exeela,fchela");       // 기본: 파싱·실행·fetch 경과 시간
        m.put("exeela", "exeela");
        m.put("fchela", "fchela");
        m.put("cpu", "prscpu,execpu,fchcpu");
        m.put("disk", "prsdsk,exedsk,fchdsk");
        m.put("query", "prsqry,exeqry,fchqry");
        SORTS = Collections.unmodifiableMap(m);
    }

    private final String sortArg;
    private final boolean waits;
    private final int printLimit;

    private TkprofOptions(String sortArg, boolean waits, int printLimit) {
        this.sortArg = sortArg;
        this.waits = waits;
        this.printLimit = printLimit;
    }

    static TkprofOptions parse(Map<String, String> p) {
        String sort = p.get("sort");
        if (sort == null || sort.isEmpty()) {
            sort = DEFAULT_SORT;
        }
        String sortArg = SORTS.get(sort);
        if (sortArg == null) {
            throw AgentException.badRequest("sort 는 " + SORTS.keySet() + " 중 하나여야 합니다.");
        }
        boolean waits = true;
        String w = p.get("waits");
        if (w != null && !w.isEmpty()) {
            if ("true".equalsIgnoreCase(w)) {
                waits = true;
            } else if ("false".equalsIgnoreCase(w)) {
                waits = false;
            } else {
                throw AgentException.badRequest("waits 는 true 또는 false 여야 합니다.");
            }
        }
        int print = 0;
        String pr = p.get("printLimit");
        if (pr != null && !pr.isEmpty()) {
            try {
                print = Integer.parseInt(pr);
            } catch (NumberFormatException e) {
                throw AgentException.badRequest("printLimit 는 정수여야 합니다.");
            }
            if (print < 1 || print > MAX_PRINT) {
                throw AgentException.badRequest("printLimit 는 1 이상 " + MAX_PRINT + " 이하여야 합니다.");
            }
        }
        return new TkprofOptions(sortArg, waits, print);
    }

    /** tkprof 의 sort= 값. */
    String getSortArg() {
        return sortArg;
    }

    boolean isWaits() {
        return waits;
    }

    /** 0 이면 print= 를 붙이지 않는다(전체). */
    int getPrintLimit() {
        return printLimit;
    }
}
