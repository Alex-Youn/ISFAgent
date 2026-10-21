package com.dbagent.agent.config;

import com.dbagent.agent.auth.Signer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * agent.properties 로드·검증(1-A1, 설계서 3.8). 필수 키 누락·잘못된 경로면 <b>기동 실패</b>(잘못된 설정을 조용히 넘기지 않는다).
 * 허용 루트는 기동 때 toRealPath()로 한 번 고정한다 - 이후 심볼릭 링크로 바꿔치기해도 루트는 변하지 않는다.
 *
 * 상대 경로(work-dir, log-dir)는 설정 파일이 있는 폴더 기준이다. 시작 스크립트의 현재 폴더에 의존하지 않기 위해서다.
 * 파일은 UTF-8로 읽는다(값은 ASCII 권장 - AIX 한글 터미널은 EUC-KR이라 UTF-8 한글 주석이 깨져 보일 수 있다).
 */
public final class AgentConfig {

    public static final String AUTH_HMAC = "hmac";
    public static final String AUTH_BEARER = "bearer";

    private static final Pattern SID_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,30}");
    private static final Pattern SID_KEY = Pattern.compile("agent\\.sid\\.([A-Za-z0-9_]+)\\.(alert-log|trace-dir|oracle-home)");
    private static final Pattern HEX64 = Pattern.compile("[0-9a-fA-F]{64}");

    /** 알려진 키. 여기에 없는 키는 오타일 가능성이 높아 기동 때 경고한다. 2단계 키도 미리 넣어 둔다. */
    private static final Set<String> KNOWN = new HashSet<String>(Arrays.asList(
            "agent.bind-address", "agent.port", "agent.auth-mode", "agent.token", "agent.token-sha256", "agent.allowed-clients",
            "agent.clock-skew-seconds", "agent.allowed-roots", "agent.oracle-home", "agent.sids", "agent.tkprof.path",
            "agent.tkprof.timeout-seconds", "agent.tkprof.max-input-mb", "agent.tkprof.nice", "agent.trace.require-identifier",
            "agent.output.max-bytes", "agent.alert-log.max-lines", "agent.alert-log.max-scan-mb", "agent.alert-log.charset",
            "agent.alert-log.timezone", "agent.work-dir", "agent.log-dir", "agent.http.threads", "agent.tls.enabled",
            "agent.tls.keystore", "agent.tls.keystore-password", "agent.tls.keystore-type", "agent.tls.protocols"));

    private final List<String> errors = new ArrayList<String>();
    private final List<String> warnings = new ArrayList<String>();

    private final Path configFile;
    private final Path baseDir;
    private final Properties props;

    private String bindAddress;
    private int port;
    private String authMode;
    private String token;
    private String tokenSha256;
    private Set<String> allowedClients = Collections.emptySet();
    private int clockSkewSeconds;
    private List<Path> allowedRoots = Collections.emptyList();
    private Map<String, SidConfig> sids = Collections.emptyMap();
    private String oracleHome;
    private boolean oracleHomeFromEnv;
    private Path tkprofPath;
    private int httpThreads;
    private int alertMaxLines;
    private long alertMaxScanBytes;
    private long outputMaxBytes;
    private Charset alertCharset;
    private ZoneId alertZone;
    private Path logDir;
    private Path workDir;

    private AgentConfig(Path configFile, Properties props) {
        this.configFile = configFile;
        this.baseDir = configFile.toAbsolutePath().getParent();
        this.props = props;
    }

    public static AgentConfig load(Path file) throws ConfigException {
        Properties p = new Properties();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
            // Windows 편집기가 붙인 UTF-8 BOM 은 첫 키 이름을 망가뜨린다(첫 줄 설정이 조용히 사라짐) - 건너뛴다.
            r.mark(1);
            if (r.read() != '﻿') {
                r.reset();
            }
            p.load(r);
        } catch (IOException e) {
            throw new ConfigException(Collections.singletonList("cannot read config file " + file + ": " + e.getMessage()));
        }
        AgentConfig c = new AgentConfig(file, p);
        c.parse();
        if (!c.errors.isEmpty()) {
            throw new ConfigException(c.errors);
        }
        return c;
    }

    // ---------------------------------------------------------------- 파싱

    private void parse() {
        checkUnknownKeys();
        checkFilePermission();

        bindAddress = required("agent.bind-address");
        if ("0.0.0.0".equals(bindAddress)) {
            warnings.add("agent.bind-address=0.0.0.0 listens on every interface; set the IP the app server connects to");
        }
        port = intVal("agent.port", 8710, 1, 65535);

        authMode = str("agent.auth-mode", AUTH_HMAC).toLowerCase(Locale.ROOT);     // 로케일(예: tr)에 따라 대소문자 변환이 달라지지 않게
        if (!AUTH_HMAC.equals(authMode) && !AUTH_BEARER.equals(authMode)) {
            errors.add("agent.auth-mode must be hmac or bearer: " + authMode);
        }
        parseToken();

        allowedClients = csvSet("agent.allowed-clients");
        clockSkewSeconds = intVal("agent.clock-skew-seconds", 60, 5, 600);

        parseRoots();
        parseSids();

        oracleHome = str("agent.oracle-home", null);
        if (oracleHome == null) {
            // (2026-10-21 오케스트레이터) OS 계정(sfo_user)의 프로파일에 ORACLE_HOME 이 이미 있으므로 따로 적지 않아도 된다.
            // 설정 파일 값이 있으면 그것이 우선이다 - 재부팅 뒤 cron·inittab 으로 기동하면 프로파일이 없어 환경 변수가 비고,
            // 한 서버에 홈이 여럿이면 tkprof 버전을 트레이스 파일의 DB 버전에 맞춰야 하기 때문이다.
            String env = System.getenv("ORACLE_HOME");
            if (env != null && !env.trim().isEmpty()) {
                oracleHome = env.trim();
                oracleHomeFromEnv = true;
            }
        }
        String tk = str("agent.tkprof.path", null);
        if (tk != null) {
            tkprofPath = toPath("agent.tkprof.path", tk);
        } else if (oracleHome != null) {
            Path home = toPath("agent.oracle-home", oracleHome);
            tkprofPath = home == null ? null : home.resolve("bin").resolve("tkprof");
        }

        httpThreads = intVal("agent.http.threads", 4, 1, 32);
        // 기본 5000: 앱의 알럿 로그 팝업이 500/1000/5000줄을 고르게 하므로(2026-10-21 오케스트레이터 결정) 에이전트 기본 상한이 더 작으면 5000줄을 못 받는다.
        alertMaxLines = intVal("agent.alert-log.max-lines", 5000, 10, 20000);
        alertMaxScanBytes = (long) intVal("agent.alert-log.max-scan-mb", 64, 1, 4096) * 1024L * 1024L;
        outputMaxBytes = intVal("agent.output.max-bytes", 5 * 1024 * 1024, 64 * 1024, 100 * 1024 * 1024);

        String cs = str("agent.alert-log.charset", null);
        if (cs == null) {
            alertCharset = Charset.defaultCharset();
        } else {
            try {
                alertCharset = Charset.forName(cs);
            } catch (RuntimeException e) {
                errors.add("agent.alert-log.charset is not a supported charset: " + cs);
            }
        }
        // 11g·12.1 알럿 로그 타임스탬프에는 시간대가 없어 DB 서버 로컬 시각이다. 기본은 JVM 시간대, 다르면 여기서 지정한다.
        String tz = str("agent.alert-log.timezone", null);
        if (tz == null) {
            alertZone = ZoneId.systemDefault();
        } else {
            try {
                alertZone = ZoneId.of(tz);
            } catch (RuntimeException e) {
                errors.add("agent.alert-log.timezone is not a valid zone id: " + tz);
            }
        }

        logDir = dir("agent.log-dir", "logs");
        workDir = dir("agent.work-dir", "work");

        if ("true".equalsIgnoreCase(str("agent.tls.enabled", "false"))) {
            errors.add("agent.tls.enabled=true is not supported yet in this build (planned for step 3-5)");
        }
    }

    private void parseToken() {
        String t = str("agent.token", null);
        String h = str("agent.token-sha256", null);
        if (AUTH_HMAC.equals(authMode)) {
            if (t == null) {
                errors.add("agent.token is required when agent.auth-mode=hmac");
            } else if (t.length() < 32) {
                errors.add("agent.token is too short (minimum 32 characters; the app generates a 256-bit token)");
            } else {
                token = t;
            }
        } else if (AUTH_BEARER.equals(authMode)) {
            if (t != null) {
                token = t;
                tokenSha256 = Signer.sha256Hex(t.getBytes(StandardCharsets.UTF_8));
            } else if (h != null && HEX64.matcher(h).matches()) {
                tokenSha256 = h.toLowerCase(Locale.ROOT);
            } else {
                errors.add("agent.token or a 64-hex agent.token-sha256 is required when agent.auth-mode=bearer");
            }
        }
    }

    private void parseRoots() {
        List<String> raw = csv("agent.allowed-roots");
        if (raw.isEmpty()) {
            errors.add("agent.allowed-roots is required (comma separated directories the agent may read)");
            return;
        }
        List<Path> roots = new ArrayList<Path>();
        for (String s : raw) {
            Path p = toPath("agent.allowed-roots", s);
            if (p == null) {
                continue;
            }
            if (!p.isAbsolute()) {
                errors.add("agent.allowed-roots entry must be an absolute path: " + s);
                continue;
            }
            try {
                if (!Files.isDirectory(p)) {
                    errors.add("agent.allowed-roots entry does not exist or is not a directory: " + s);
                    continue;
                }
                roots.add(p.toRealPath());
            } catch (IOException e) {
                errors.add("agent.allowed-roots entry cannot be resolved: " + s + " (" + e.getMessage() + ")");
            }
        }
        allowedRoots = Collections.unmodifiableList(roots);
    }

    private void parseSids() {
        List<String> names = csv("agent.sids");
        if (names.isEmpty()) {
            errors.add("agent.sids is required (comma separated, e.g. PATDB1,PATDB2)");
            return;
        }
        Map<String, SidConfig> map = new LinkedHashMap<String, SidConfig>();
        for (String sid : names) {
            if (!SID_PATTERN.matcher(sid).matches()) {
                errors.add("agent.sids entry has invalid characters (letters, digits, underscore only): " + sid);
                continue;
            }
            if (map.containsKey(sid)) {
                errors.add("agent.sids has a duplicate entry: " + sid);
                continue;
            }
            String prefix = "agent.sid." + sid + ".";
            String alert = str(prefix + "alert-log", null);
            if (alert == null) {
                errors.add(prefix + "alert-log is required");
                continue;
            }
            Path alertPath = toPath(prefix + "alert-log", alert);
            if (alertPath == null) {
                continue;
            }
            if (!alertPath.isAbsolute()) {
                errors.add(prefix + "alert-log must be an absolute path: " + alert);
                continue;
            }
            String trace = str(prefix + "trace-dir", null);
            Path tracePath = null;
            if (trace != null) {
                tracePath = toPath(prefix + "trace-dir", trace);
                if (tracePath == null) {
                    continue;
                }
                if (!tracePath.isAbsolute()) {
                    errors.add(prefix + "trace-dir must be an absolute path: " + trace);
                    continue;
                }
            }
            // 알럿 로그가 아직 없거나 루트 밖이어도 기동은 막지 않는다(health 의 alertLogOk=false 로 보인다). 대신 시작 때 경고한다.
            if (!Files.exists(alertPath)) {
                warnings.add(prefix + "alert-log does not exist now: " + alert);
            } else if (!allowedRoots.isEmpty()) {
                try {
                    if (!underAnyRoot(alertPath.toRealPath())) {
                        warnings.add(prefix + "alert-log is outside agent.allowed-roots, requests will be refused: " + alert);
                    }
                } catch (IOException e) {
                    warnings.add(prefix + "alert-log cannot be resolved: " + alert);
                }
            }
            map.put(sid, new SidConfig(sid, alertPath, tracePath, str(prefix + "oracle-home", null)));
        }
        sids = Collections.unmodifiableMap(map);
    }

    private boolean underAnyRoot(Path real) {
        for (Path r : allowedRoots) {
            if (real.startsWith(r)) {
                return true;
            }
        }
        return false;
    }

    private void checkUnknownKeys() {
        for (String k : props.stringPropertyNames()) {
            if (!KNOWN.contains(k) && !SID_KEY.matcher(k).matches()) {
                warnings.add("unknown key ignored (typo?): " + k);
            }
        }
    }

    /** 토큰 원문이 들어 있는 파일이라 소유자 외에 읽을 수 있으면 경고한다. Windows 처럼 POSIX 권한이 없는 곳은 건너뛴다. */
    private void checkFilePermission() {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(configFile);
            if (perms.contains(PosixFilePermission.GROUP_READ) || perms.contains(PosixFilePermission.GROUP_WRITE)
                    || perms.contains(PosixFilePermission.OTHERS_READ) || perms.contains(PosixFilePermission.OTHERS_WRITE)) {
                warnings.add("config file is readable by group/others (it holds the token): run chmod 600 " + configFile);
            }
        } catch (UnsupportedOperationException e) {
            // POSIX 권한 없는 파일시스템
        } catch (IOException e) {
            warnings.add("cannot check config file permissions: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 값 읽기 도우미

    private String str(String key, String def) {
        String v = props.getProperty(key);
        if (v == null) {
            return def;
        }
        v = v.trim();
        return v.isEmpty() ? def : v;
    }

    private String required(String key) {
        String v = str(key, null);
        if (v == null) {
            errors.add(key + " is required");
            return "";
        }
        return v;
    }

    private int intVal(String key, int def, int min, int max) {
        String v = str(key, null);
        if (v == null) {
            return def;
        }
        try {
            long n = Long.parseLong(v);
            if (n < min || n > max) {
                errors.add(key + " must be between " + min + " and " + max + ": " + v);
                return def;
            }
            return (int) n;
        } catch (NumberFormatException e) {
            errors.add(key + " must be an integer: " + v);
            return def;
        }
    }

    private List<String> csv(String key) {
        List<String> out = new ArrayList<String>();
        String v = str(key, null);
        if (v == null) {
            return out;
        }
        for (String s : v.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private Set<String> csvSet(String key) {
        return Collections.unmodifiableSet(new HashSet<String>(csv(key)));
    }

    private Path dir(String key, String def) {
        String v = str(key, def);
        Path p = toPath(key, v);
        if (p == null) {
            return baseDir;   // 오류는 이미 기록했다(load 가 설정 오류로 끝낸다) - 여기서는 NPE 를 피하려고 아무 폴더나 돌려준다
        }
        return p.isAbsolute() ? p.normalize() : baseDir.resolve(p).normalize();
    }

    /**
     * 설정 값을 경로로 바꾼다. 이 JVM 의 파일 이름 인코딩(sun.jnu.encoding)이 표현하지 못하는 글자가 들어 있으면(예: LANG=C 인 AIX 서버에 한글 경로)
     * Paths.get 이 InvalidPathException 을 던져 스택 트레이스와 함께 죽는데, 다른 설정 오류처럼 한 번에 알리도록 오류로 기록하고 null 을 돌려준다
     * (2026-10-22 1-C 시험: 컨테이너 LANG=C 에서 한글 파일 이름 재현).
     */
    private Path toPath(String key, String value) {
        try {
            return Paths.get(value);
        } catch (InvalidPathException e) {
            errors.add(key + " has a path this JVM cannot use (characters the file name encoding " + System.getProperty("sun.jnu.encoding")
                    + " cannot represent) - set LANG/LC_ALL to the locale that matches the path or use an ASCII path: " + value.replaceAll("[^ -~]", "?"));
            return null;
        }
    }

    // ---------------------------------------------------------------- 접근자

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public int getPort() {
        return port;
    }

    public String getAuthMode() {
        return authMode;
    }

    /** hmac 원문 토큰. bearer 에서 해시만 설정했으면 null. */
    public String getToken() {
        return token;
    }

    public String getTokenSha256() {
        return tokenSha256;
    }

    public Set<String> getAllowedClients() {
        return allowedClients;
    }

    public int getClockSkewSeconds() {
        return clockSkewSeconds;
    }

    public List<Path> getAllowedRoots() {
        return allowedRoots;
    }

    public Map<String, SidConfig> getSids() {
        return sids;
    }

    /** 설정 파일 값, 없으면 에이전트를 띄운 계정 환경의 ORACLE_HOME. 둘 다 없으면 null. */
    public String getOracleHome() {
        return oracleHome;
    }

    public boolean isOracleHomeFromEnv() {
        return oracleHomeFromEnv;
    }

    /** 전역 tkprof 경로. ORACLE_HOME 도 경로도 없으면 null. */
    public Path getTkprofPath() {
        return tkprofPath;
    }

    public int getHttpThreads() {
        return httpThreads;
    }

    public int getAlertMaxLines() {
        return alertMaxLines;
    }

    public long getAlertMaxScanBytes() {
        return alertMaxScanBytes;
    }

    public long getOutputMaxBytes() {
        return outputMaxBytes;
    }

    public Charset getAlertCharset() {
        return alertCharset;
    }

    public ZoneId getAlertZone() {
        return alertZone;
    }

    public Path getLogDir() {
        return logDir;
    }

    public Path getWorkDir() {
        return workDir;
    }
}
