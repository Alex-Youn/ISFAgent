package com.dbagent.agent;

import com.dbagent.agent.client.AgentClient;
import com.dbagent.agent.config.AgentConfig;
import com.dbagent.agent.config.ConfigException;
import com.dbagent.agent.http.AgentHttpServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;

/**
 * ISFAgent 진입점. Java 8 문법만 쓴다(var·record·switch 식·텍스트 블록 금지).
 *
 *   --version
 *   --config <agent.properties>                        서버 기동
 *   --config <agent.properties> --health               실행 중인 에이전트의 health 를 서명해서 호출
 *   --config <agent.properties> --get "<path?query>"   서명된 GET (예: "/v1/alert-log/tail?sid=PATDB1&lines=20")
 *
 * 콘솔·로그 메시지는 영문으로 쓴다: 폐쇄망 AIX 로케일에서 한글이 '?'로 깨진 전례가 있다(2026-10-21 HttpProbe 결과, probe.log).
 */
public final class AgentMain {

    private AgentMain() {
    }

    public static void main(String[] args) throws Exception {
        String config = null;
        String get = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--help".equals(a) || "-h".equals(a)) {
                usage();
                return;
            } else if ("--version".equals(a)) {
                System.out.println("dbagent-agent " + version());
                return;
            } else if ("--config".equals(a) && i + 1 < args.length) {
                config = args[++i];
            } else if ("--health".equals(a)) {
                get = "/v1/health";
            } else if ("--get".equals(a) && i + 1 < args.length) {
                get = args[++i];
            } else {
                System.err.println("Unknown or incomplete argument: " + a);
                usage();
                System.exit(2);
                return;
            }
        }
        if (config == null) {
            usage();
            System.exit(2);
            return;
        }
        AgentConfig cfg = loadConfig(config);
        if (get != null) {
            System.exit(AgentClient.get(cfg, get));
            return;
        }
        runServer(cfg);
    }

    private static AgentConfig loadConfig(String file) {
        Path p = Paths.get(file);
        if (!Files.isRegularFile(p)) {
            System.err.println("CONFIG ERROR: config file not found: " + file);
            System.exit(1);
        }
        try {
            return AgentConfig.load(p);
        } catch (ConfigException e) {
            for (String err : e.getErrors()) {
                System.err.println("CONFIG ERROR: " + err);
            }
            System.err.println("Fix agent.properties and start again (" + e.getErrors().size() + " error(s)).");
            System.exit(1);
            return null;
        }
    }

    private static void runServer(AgentConfig cfg) throws Exception {
        AgentLog.init(cfg.getLogDir());
        for (String w : cfg.getWarnings()) {
            AgentLog.warn("config: " + w);
        }
        final AgentHttpServer server = new AgentHttpServer(cfg);
        try {
            server.start();
        } catch (java.io.IOException e) {
            AgentLog.error("cannot start the HTTP server on " + cfg.getBindAddress() + ":" + cfg.getPort(), e);
            System.err.println("Cannot listen on " + cfg.getBindAddress() + ":" + cfg.getPort() + " - " + e.getMessage());
            System.exit(1);
            return;
        }
        final CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread("agent-shutdown") {
            @Override
            public void run() {
                AgentLog.info("shutting down");
                server.stop();
                stop.countDown();
            }
        });
        String msg = "dbagent-agent " + version() + " listening on " + cfg.getBindAddress() + ":" + cfg.getPort()
                + " (auth=" + cfg.getAuthMode() + ", sids=" + cfg.getSids().keySet() + ", java=" + System.getProperty("java.version")
                + ", user=" + System.getProperty("user.name") + ")";
        AgentLog.info(msg);
        AgentLog.info("oracle-home=" + (cfg.getOracleHome() == null ? "(not set, tkprof unavailable)"
                : cfg.getOracleHome() + (cfg.isOracleHomeFromEnv() ? " (from environment)" : " (from agent.properties)")));
        System.out.println(msg);
        stop.await();
    }

    /** jar 매니페스트의 Implementation-Version. IDE에서 클래스로 직접 실행하면 없으므로 dev 로 표시한다. */
    public static String version() {
        String v = AgentMain.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    private static void usage() {
        System.out.println("dbagent-agent " + version());
        System.out.println("Usage: java -jar dbagent-agent.jar --version");
        System.out.println("       java -jar dbagent-agent.jar --config <agent.properties>");
        System.out.println("       java -jar dbagent-agent.jar --config <agent.properties> --health");
        System.out.println("       java -jar dbagent-agent.jar --config <agent.properties> --get \"/v1/alert-log/tail?sid=<SID>&lines=20\"");
    }
}
