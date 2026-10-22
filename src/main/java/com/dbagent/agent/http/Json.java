package com.dbagent.agent.http;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

/**
 * 외부 라이브러리 없이 쓰는 최소 JSON 작성기(설계서 T1: 의존성 0). Map·Collection·String·Number·Boolean·null 만 다룬다.
 * ASCII 밖의 문자는 \\uXXXX 로 내보낸다 - 응답이 순수 ASCII 가 되어 JVM·로케일의 기본 인코딩에 영향받지 않는다
 * (폐쇄망 AIX 에서 한글이 깨진 전례, 2026-10-21).
 */
public final class Json {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Json() {
    }

    static String write(Object v) {
        StringBuilder sb = new StringBuilder(256);
        try {
            append(sb, v);
        } catch (IOException e) {
            throw new IllegalStateException(e);     // StringBuilder 는 IOException 을 내지 않는다
        }
        return sb.toString();
    }

    /**
     * 응답을 통째로 문자열·바이트 배열로 만들지 않고 바로 스트림에 쓴다. 5MB 응답이 문자열(char 2바이트)·바이트 배열로
     * 여러 벌 복사되면 스레드 4개 x 수십 MB 로 기본 힙(-Xmx128m)을 넘을 수 있어서다. 출력이 순수 ASCII 라 문자 수 = 바이트 수.
     */
    static void write(Object v, Appendable out) throws IOException {
        append(out, v);
    }

    /** write 가 낼 바이트 수(Content-Length 용). 같은 코드로 세므로 실제 출력과 어긋나지 않는다. */
    static long length(Object v) {
        Counter c = new Counter();
        try {
            append(c, v);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return c.n;
    }

    /** 문자열 하나가 JSON 으로 나갈 때의 바이트 수(따옴표 제외). 응답 크기 상한을 실제 출력 기준으로 재는 데 쓴다. */
    public static long escapedLength(String s) {
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            n += escapedLength(s.charAt(i));
        }
        return n;
    }

    /** 글자 하나가 JSON 으로 나갈 때의 바이트 수(1, 2 또는 6). 큰 텍스트를 응답 상한에서 자를 지점을 찾는 데 쓴다. */
    public static int escapedLength(char c) {
        if (c == '"' || c == '\\' || c == '\n' || c == '\r' || c == '\t') {
            return 2;
        }
        return (c < 0x20 || c > 0x7E) ? 6 : 1;
    }

    private static final class Counter implements Appendable {
        long n;

        @Override
        public Appendable append(CharSequence csq) {
            n += csq.length();
            return this;
        }

        @Override
        public Appendable append(CharSequence csq, int start, int end) {
            n += end - start;
            return this;
        }

        @Override
        public Appendable append(char c) {
            n++;
            return this;
        }
    }

    private static void append(Appendable sb, Object v) throws IOException {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            string(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v).booleanValue() ? "true" : "false");
        } else if (v instanceof Number) {
            sb.append(v.toString());
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                string(sb, String.valueOf(e.getKey()));
                sb.append(':');
                append(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Collection) {
            sb.append('[');
            boolean first = true;
            for (Object o : (Collection<?>) v) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                append(sb, o);
            }
            sb.append(']');
        } else {
            string(sb, v.toString());
        }
    }

    private static void string(Appendable sb, String s) throws IOException {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c > 0x7E) {
                        // String.format 은 글자마다 Formatter 를 만들어 한글 위주 응답(수 MB)에서 눈에 띄게 느리다. 서로게이트 쌍도
                        // 반쪽씩 \\uXXXX 로 내보내면 JSON 파서가 다시 합친다. U+2028/2029 도 이 경로로 이스케이프된다.
                        sb.append('\\').append('u').append(HEX[(c >> 12) & 0xF]).append(HEX[(c >> 8) & 0xF])
                                .append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
