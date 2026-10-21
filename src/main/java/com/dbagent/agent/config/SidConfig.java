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

    /** SID별 ORACLE_HOME. 없으면 null(전역 값을 쓴다). */
    public String getOracleHome() {
        return oracleHome;
    }
}
