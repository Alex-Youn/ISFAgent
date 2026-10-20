import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/** HttpProbe 의 시험 서버 - 요청 메서드·시험 헤더·본문 길이와 JVM 정보만 JSON으로 돌려준다. 파일·DB는 건드리지 않는다. */
public class HttpProbeServer {

    public static void start(String bind, int port) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.createContext("/probe", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws java.io.IOException {
                try {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    InputStream in = ex.getRequestBody();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    String host;
                    try {
                        host = InetAddress.getLocalHost().getHostName();
                    } catch (Exception e) {
                        host = "unknown";
                    }
                    String json = "{\"ok\":true,\"method\":" + q(ex.getRequestMethod())
                            + ",\"xAgentTs\":" + q(ex.getRequestHeaders().getFirst("X-Agent-Ts"))
                            + ",\"xAgentSig\":" + q(ex.getRequestHeaders().getFirst("X-Agent-Sig"))
                            + ",\"bodyLength\":" + bo.size()
                            + ",\"remote\":" + q(String.valueOf(ex.getRemoteAddress()))
                            + ",\"host\":" + q(host)
                            + ",\"javaVersion\":" + q(System.getProperty("java.version"))
                            + ",\"vmName\":" + q(System.getProperty("java.vm.name"))
                            + ",\"os\":" + q(System.getProperty("os.name") + " " + System.getProperty("os.arch"))
                            + ",\"user\":" + q(System.getProperty("user.name")) + "}";
                    byte[] out = json.getBytes("UTF-8");
                    ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    ex.sendResponseHeaders(200, out.length);
                    OutputStream os = ex.getResponseBody();
                    os.write(out);
                    os.close();
                } finally {
                    ex.close();
                }
            }
        });
        server.start();
        System.out.println("HttpProbe listening on " + bind + ":" + port + " (user=" + System.getProperty("user.name")
                + ", vm=" + System.getProperty("java.vm.name") + ") - 확인이 끝나면 종료하세요");
    }

    private static String q(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.append('"').toString();
    }
}
