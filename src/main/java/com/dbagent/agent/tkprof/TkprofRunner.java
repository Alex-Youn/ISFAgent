package com.dbagent.agent.tkprof;

import com.dbagent.agent.AgentException;
import com.dbagent.agent.AgentLog;
import com.dbagent.agent.config.AgentConfig;
import com.dbagent.agent.config.SidConfig;
import com.dbagent.agent.http.Json;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * tkprof 실행기(2-A2, 2-A3, 설계서 3.5·3.6).
 *
 *   - 셸을 거치지 않는다: ProcessBuilder 에 <b>인자 배열</b>로 넘기고, 고정 인자(sys=no aggregate=yes waits= sort= [print=])만 쓴다
 *   - 환경 변수는 비운 뒤 ORACLE_HOME·PATH·LIBPATH(AIX)·LD_LIBRARY_PATH(Linux)·LANG=C 만 넘긴다 - 에이전트를 띄운 계정의 환경에 따라
 *     결과가 달라지거나 비밀 값이 자식 프로세스로 새는 것을 막는다
 *   - 동시 실행은 agent.tkprof.max-concurrent 건(기본 2). 자리가 없으면 최대 30초 기다렸다가 429 BUSY (2026-10-21 오케스트레이터)
 *   - agent.tkprof.timeout-seconds 를 넘기면 프로세스를 강제 종료하고 504 TIMEOUT
 *   - 출력 파일(.prf)과 표준 출력 파일(.out)은 작업 폴더에 UUID 이름으로 만들고 응답 직후 finally 에서 지운다. 기동 때 남은 것도 지운다
 *   - 응답 텍스트는 agent.output.max-bytes(JSON 으로 나가는 실제 바이트 기준)에서 줄 단위로 자르고 truncated=true
 *
 * 오류 응답에는 tkprof 의 출력이나 경로를 싣지 않는다(서버 로그에만 남긴다).
 */
public final class TkprofRunner {

    /** 자리가 날 때까지 기다리는 최대 시간. 이보다 오래 기다리게 하지 않고 BUSY 로 알린다. */
    private static final long QUEUE_WAIT_SECONDS = 30L;
    /** JSON 의 나머지 필드(elapsedMs 등)가 쓸 여유. */
    private static final long RESPONSE_OVERHEAD = 512L;
    private static final Pattern OWN_FILE = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(prf|out)");

    private final AgentConfig cfg;
    private final Semaphore slots;

    public TkprofRunner(AgentConfig cfg) {
        this.cfg = cfg;
        this.slots = new Semaphore(cfg.getTkprofMaxConcurrent(), true);     // 공정 순서: 먼저 온 요청이 먼저 자리를 얻는다
    }

    /** health 의 busy: 동시 실행 자리가 모두 찼다. */
    public boolean isBusy() {
        return slots.availablePermits() == 0;
    }

    /** health 의 tkprofOk: 등록된 SID 중 하나라도 실행 가능한 tkprof 가 있다. */
    public boolean isAvailable() {
        for (SidConfig sc : cfg.getSids().values()) {
            Tool t = locate(sc);
            if (t != null && usable(t.tkprof)) {
                return true;
            }
        }
        return false;
    }

    /** 기동 때 이전 실행이 남긴 출력 파일을 지운다(에이전트가 실행 도중 죽은 경우). 우리 이름 규칙의 파일만 지운다. */
    public void cleanWorkDir() {
        Path work = cfg.getWorkDir();
        if (!Files.isDirectory(work)) {
            return;
        }
        int n = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(work)) {
            for (Path f : ds) {
                if (OWN_FILE.matcher(f.getFileName().toString()).matches() && Files.isRegularFile(f)) {
                    Files.deleteIfExists(f);
                    n++;
                }
            }
        } catch (IOException e) {
            AgentLog.warn("work dir cleanup failed: " + e);
        }
        if (n > 0) {
            AgentLog.info("removed " + n + " leftover tkprof file(s) from the work directory");
        }
    }

    Map<String, Object> run(SidConfig sc, ResolvedTrace trace, TkprofOptions opt) {
        Tool tool = locate(sc);
        if (tool == null || !usable(tool.tkprof)) {
            throw new AgentException(503, "TKPROF_UNAVAILABLE",
                    "이 서버에서 tkprof 를 실행할 수 없습니다(ORACLE_HOME 또는 agent.tkprof.path 설정을 확인하세요).");
        }
        boolean acquired = false;
        try {
            acquired = slots.tryAcquire(QUEUE_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentException(500, "INTERNAL", "요청이 중단되었습니다.");
        }
        if (!acquired) {
            throw new AgentException(429, "BUSY", "다른 tkprof 분석이 진행 중입니다. 잠시 후 다시 시도하세요.");
        }
        Path prf = null;
        Path out = null;
        try {
            Path work = cfg.getWorkDir();
            Files.createDirectories(work);
            String id = UUID.randomUUID().toString();
            prf = work.resolve(id + ".prf");
            out = work.resolve(id + ".out");
            long t0 = System.nanoTime();
            execute(tool, trace, opt, work, prf, out);
            long elapsedMs = (System.nanoTime() - t0) / 1000000L;
            return result(prf, trace, elapsedMs);
        } catch (IOException e) {
            AgentLog.warn("tkprof run failed sid=" + sc.getSid() + " error=" + e);
            throw new AgentException(500, "INTERNAL", "tkprof 를 실행하는 중 오류가 발생했습니다.");
        } finally {
            delete(prf);
            delete(out);
            slots.release();
        }
    }

    // ---------------------------------------------------------------- 실행

    private void execute(Tool tool, ResolvedTrace trace, TkprofOptions opt, Path work, Path prf, Path out) throws IOException {
        List<String> cmd = new ArrayList<String>();
        if (cfg.getTkprofNice() > 0) {
            cmd.add("nice");
            cmd.add("-n");
            cmd.add(String.valueOf(cfg.getTkprofNice()));
        }
        cmd.add(tool.tkprof.toString());
        cmd.add(trace.getFile().toString());
        cmd.add(prf.toString());
        cmd.add("sys=no");
        cmd.add("aggregate=yes");
        cmd.add("waits=" + (opt.isWaits() ? "yes" : "no"));
        cmd.add("sort=" + opt.getSortArg());
        if (opt.getPrintLimit() > 0) {
            cmd.add("print=" + opt.getPrintLimit());
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(work.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(out.toFile());        // 파이프로 받으면 출력이 많을 때 막힐 수 있어 파일로 받는다
        Map<String, String> env = pb.environment();
        env.clear();
        String home = tool.home.toString();
        String lib = tool.home.resolve("lib").toString();
        env.put("ORACLE_HOME", home);
        env.put("PATH", tool.home.resolve("bin") + File.pathSeparator + "/usr/bin" + File.pathSeparator + "/bin");
        env.put("LIBPATH", lib);                // AIX
        env.put("LD_LIBRARY_PATH", lib);        // Linux
        env.put("LANG", "C");

        Process p = pb.start();
        try {
            p.getOutputStream().close();        // 표준 입력을 바로 닫아 tkprof 가 입력을 기다리지 않게 한다
            if (!p.waitFor(cfg.getTkprofTimeoutSeconds(), TimeUnit.SECONDS)) {
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
                AgentLog.warn("tkprof killed after " + cfg.getTkprofTimeoutSeconds() + "s");
                throw new AgentException(504, "TIMEOUT", "tkprof 가 제한 시간(" + cfg.getTkprofTimeoutSeconds() + "초) 안에 끝나지 않아 중단했습니다.");
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new AgentException(500, "INTERNAL", "요청이 중단되었습니다.");
        }
        int code = p.exitValue();
        if (code != 0 || !Files.isRegularFile(prf)) {
            AgentLog.warn("tkprof failed exit=" + code + " output=" + head(out));
            throw new AgentException(500, "INTERNAL", "tkprof 실행에 실패했습니다(종료 코드 " + code + ").");
        }
    }

    // ---------------------------------------------------------------- 결과

    private Map<String, Object> result(Path prf, ResolvedTrace trace, long elapsedMs) throws IOException {
        long cap = cfg.getOutputMaxBytes();
        long size = Files.size(prf);
        int toRead = (int) Math.min(size, cap + 1);
        byte[] buf = new byte[toRead];
        try (InputStream in = Files.newInputStream(prf)) {
            int off = 0;
            int n;
            while (off < toRead && (n = in.read(buf, off, toRead - off)) > 0) {
                off += n;
            }
        }
        boolean truncated = size > cap;
        String s = new String(buf, 0, (int) Math.min(toRead, cap), StandardCharsets.UTF_8);

        // 응답 크기는 JSON 으로 나가는 실제 바이트로 잰다 - 한글은 \\uXXXX(6바이트)라 글자 수로 재면 상한의 몇 배까지 커진다.
        long budget = cap - RESPONSE_OVERHEAD;
        long used = 0;
        int end = s.length();
        for (int i = 0; i < s.length(); i++) {
            used += Json.escapedLength(s.charAt(i));
            if (used > budget) {
                end = i;
                truncated = true;
                break;
            }
        }
        if (truncated) {
            int nl = s.lastIndexOf('\n', end - 1);      // 줄 중간에서 끊지 않는다
            if (nl > 0) {
                end = nl + 1;
            }
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("text", s.substring(0, end));
        m.put("truncated", truncated);
        m.put("elapsedMs", elapsedMs);
        m.put("traceBytes", trace.getSizeBytes());
        return m;
    }

    // ---------------------------------------------------------------- 도구 위치

    /** tkprof 실행 파일과 그 ORACLE_HOME. SID 별 oracle-home 이 있으면 그것이 우선이고, 없으면 전역 설정을 쓴다. */
    private Tool locate(SidConfig sc) {
        try {
            if (sc.getOracleHome() != null) {
                Path home = Paths.get(sc.getOracleHome());
                return new Tool(home.resolve("bin").resolve("tkprof"), home);
            }
        } catch (InvalidPathException e) {
            return null;
        }
        Path tk = cfg.getTkprofPath();
        if (tk == null) {
            return null;
        }
        Path home = null;
        if (cfg.getOracleHome() != null) {
            try {
                home = Paths.get(cfg.getOracleHome());
            } catch (InvalidPathException e) {
                return null;
            }
        } else if (tk.getParent() != null) {
            home = tk.getParent().getParent();        // agent.tkprof.path 만 지정한 경우: <home>/bin/tkprof 로 본다
        }
        return home == null ? null : new Tool(tk, home);
    }

    private static boolean usable(Path tkprof) {
        return Files.isRegularFile(tkprof) && Files.isExecutable(tkprof);
    }

    private static void delete(Path f) {
        if (f == null) {
            return;
        }
        try {
            Files.deleteIfExists(f);
        } catch (IOException e) {
            AgentLog.warn("cannot delete work file: " + e);
        }
    }

    /** 로그용: 표준 출력 파일의 앞부분(경로가 섞일 수 있어 응답에는 쓰지 않는다). */
    private static String head(Path out) {
        try (InputStream in = Files.newInputStream(out)) {
            byte[] b = new byte[600];
            int n = in.read(b);
            return n <= 0 ? "" : AgentLog.safe(new String(b, 0, n, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return "(unreadable)";
        }
    }

    private static final class Tool {
        final Path tkprof;
        final Path home;

        Tool(Path tkprof, Path home) {
            this.tkprof = tkprof;
            this.home = home;
        }
    }
}
