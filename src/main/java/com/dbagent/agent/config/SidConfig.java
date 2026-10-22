package com.dbagent.agent.config;

import java.nio.file.Path;

/** SID 하나의 파일 위치. 알럿 로그는 1단계, 트레이스 디렉터리·ORACLE_HOME 은 2단계(tkprof)에서 쓴다. */
public final class SidConfig {

    private final String sid;
    private final Path alertLog;
    private final Path traceDir;
    private final String oracleHome;

    SidConfig(String sid, Path alertLog, Path traceDir, String oracleHome) {
        this.sid = sid;
        this.alertLog = alertLog;
        this.traceDir = traceDir;
        this.oracleHome = oracleHome;
    }

    public String getSid() {
        return sid;
    }

    public Path getAlertLog() {
        return alertLog;
    }

    /** 2단계용. 없으면 null. */
    public Path getTraceDir() {
        return traceDir;
    }

    /**
     * 트레이스 파일을 찾을 폴더. 따로 적지 않으면 알럿 로그가 있는 폴더다 - 11g 이상 ADR 에서는 alert_&lt;SID&gt;.log 와 .trc 가
     * 같은 trace 폴더에 생긴다(2026-10-21 오케스트레이터 확인: 세션 트레이스가 알럿 로그 위치에 생김).
     */
    public Path getEffectiveTraceDir() {
        return traceDir != null ? traceDir : alertLog.getParent();
    }

    /** SID별 ORACLE_HOME. 없으면 null(전역 값을 쓴다). */
    public String getOracleHome() {
        return oracleHome;
    }
}
