package com.dbagent.agent;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 에이전트 로그(1-A8). 파일은 5MB x 5개로 돌려 쓰고(디스크를 채우지 않게), 콘솔에는 경고 이상만 낸다.
 * 요청 로그에는 메타데이터(메서드·경로·상태·소요 시간·클라이언트 IP)만 남기고 쿼리 값·응답 내용은 남기지 않는다.
 * 메시지는 영문으로 쓴다: 폐쇄망 AIX 로케일에서 한글이 '?'로 깨진 전례(2026-10-21 HttpProbe 결과).
 */
public final class AgentLog {

    private static final Logger LOG = Logger.getLogger("dbagent.agent");

    private AgentLog() {
    }

    public static void init(Path logDir) throws IOException {
        Files.createDirectories(logDir);
        LOG.setUseParentHandlers(false);
        for (Handler h : LOG.getHandlers()) {
            LOG.removeHandler(h);
        }
        LOG.setLevel(Level.INFO);
        OneLineFormatter fmt = new OneLineFormatter();
        FileHandler fh = new FileHandler(logDir.resolve("agent.%g.log").toString(), 5 * 1024 * 1024, 5, true);
        fh.setEncoding("UTF-8");
        fh.setFormatter(fmt);
        fh.setLevel(Level.INFO);
        LOG.addHandler(fh);
        ConsoleHandler ch = new ConsoleHandler();
        ch.setFormatter(fmt);
        ch.setLevel(Level.WARNING);
        LOG.addHandler(ch);
    }

    public static void info(String msg) {
        LOG.log(Level.INFO, msg);
    }

    public static void warn(String msg) {
        LOG.log(Level.WARNING, msg);
    }

    public static void error(String msg, Throwable t) {
        LOG.log(Level.SEVERE, msg, t);
    }

    private static final int SAFE_MAX = 300;

    /**
     * 호출자가 보낸 값(메서드·경로)을 로그에 넣기 전에 거친다. JDK 내장 서버는 요청 줄의 단독 LF 를 메서드에 그대로 넣어 주므로
     * ("GET\nFORGED ..." -> 메서드 "GET\nFORGED"), 인증 전 요청만으로 로그에 가짜 줄을 만들 수 있었다(2026-10-21 점검 재현).
     * 제어 문자·ASCII 밖 문자는 \\uXXXX 로 바꾸고 길이도 자른다(긴 경로로 로그를 부풀리지 않게).
     */
    public static String safe(String s) {
        if (s == null) {
            return "null";
        }
        int n = Math.min(s.length(), SAFE_MAX);
        StringBuilder sb = new StringBuilder(n + 16);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                sb.append("\\u");
                String h = Integer.toHexString(c);
                for (int k = h.length(); k < 4; k++) {
                    sb.append('0');
                }
                sb.append(h);
            } else {
                sb.append(c);
            }
        }
        if (s.length() > SAFE_MAX) {
            sb.append("...(").append(s.length()).append(" chars)");
        }
        return sb.toString();
    }

    /** 한 줄 형식. 날짜 포맷은 스레드마다 따로 쓴다(SimpleDateFormat은 스레드 안전하지 않음). */
    private static final class OneLineFormatter extends Formatter {
        private final ThreadLocal<SimpleDateFormat> df = new ThreadLocal<SimpleDateFormat>() {
            @Override
            protected SimpleDateFormat initialValue() {
                return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            }
        };

        @Override
        public String format(LogRecord r) {
            StringBuilder sb = new StringBuilder(160);
            sb.append(df.get().format(new Date(r.getMillis()))).append(' ').append(r.getLevel().getName()).append(' ')
                    .append(formatMessage(r)).append('\n');
            if (r.getThrown() != null) {
                StringWriter sw = new StringWriter();
                r.getThrown().printStackTrace(new PrintWriter(sw));
                sb.append(sw);
            }
            return sb.toString();
        }
    }
}
