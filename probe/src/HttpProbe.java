import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 선택형 에이전트 0-3단계 시험(2026-10-20): 이 서버의 JVM에 com.sun.net.httpserver 가 있고, 앱 서버에서 그 포트로 접속되는지 확인한다.
 * 에이전트 본체가 아니다 - 인증이 없으므로 확인이 끝나면 바로 종료한다. Java 8 문법만 쓴다.
 *
 * 사용:
 *   java -cp httpprobe.jar HttpProbe --check                 클래스 존재·로드 위치·JVM 정보
 *   java -cp httpprobe.jar HttpProbe bindAddr port           시험 서버 기동 (GET/POST /probe)
 *   java -cp httpprobe.jar HttpProbe --get url               클라이언트(HttpURLConnection - 앱이 에이전트에 접속하는 방식과 같다)
 *   java -cp httpprobe.jar HttpProbe --post url body         헤더 X-Agent-Ts·X-Agent-Sig 를 붙여 POST
 */
public class HttpProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        if ("--check".equals(args[0])) {
            check();
        } else if ("--get".equals(args[0]) && args.length >= 2) {
            call("GET", args[1], null);
        } else if ("--post".equals(args[0]) && args.length >= 3) {
            call("POST", args[1], args[2]);
        } else if (args.length >= 2 && !args[0].startsWith("--")) {
            // 서버 클래스는 이 분기에서만 로드된다 - httpserver 가 없어도 --check 는 결과를 출력할 수 있게 분리했다
            Class.forName("HttpProbeServer").getMethod("start", String.class, int.class).invoke(null, args[0], Integer.valueOf(args[1]));
        } else {
            usage();
        }
    }

    static void usage() {
        System.out.println("usage: HttpProbe --check | bindAddr port | --get url | --post url body");
    }

    static void check() {
        System.out.println("java.version=" + System.getProperty("java.version"));
        System.out.println("java.vm.name=" + System.getProperty("java.vm.name"));
        System.out.println("java.vm.version=" + System.getProperty("java.vm.version"));
        System.out.println("java.runtime.version=" + System.getProperty("java.runtime.version"));
        System.out.println("java.home=" + System.getProperty("java.home"));
        System.out.println("os.name=" + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        System.out.println("user.name=" + System.getProperty("user.name"));
        String[] names = {"com.sun.net.httpserver.HttpServer", "com.sun.net.httpserver.HttpsServer", "javax.crypto.Mac"};
        boolean all = true;
        for (String n : names) {
            try {
                Class<?> c = Class.forName(n);
                java.security.CodeSource cs = c.getProtectionDomain() == null ? null : c.getProtectionDomain().getCodeSource();
                System.out.println("CLASS " + n + " = OK (loader=" + c.getClassLoader() + ", location=" + (cs == null ? "boot" : cs.getLocation()) + ")");
            } catch (Throwable t) {
                all = false;
                System.out.println("CLASS " + n + " = FAIL (" + t + ")");
            }
        }
        System.out.println(all ? "RESULT=OK" : "RESULT=FAIL");
    }

    static void call(String method, String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(5000);
        c.setRequestMethod(method);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("X-Agent-Ts", String.valueOf(System.currentTimeMillis()));
            c.setRequestProperty("X-Agent-Sig", "test");
            byte[] b = body.getBytes("UTF-8");
            OutputStream os = c.getOutputStream();
            os.write(b);
            os.close();
        }
        int code;
        try {
            code = c.getResponseCode();
        } catch (Exception e) {
            System.out.println("FAIL " + e);
            return;
        }
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
        }
        System.out.println("HTTP " + code);
        System.out.println(new String(out.toByteArray(), "UTF-8"));
    }
}
