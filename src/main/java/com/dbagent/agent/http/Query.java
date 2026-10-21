package com.dbagent.agent.http;

import com.dbagent.agent.AgentException;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

/** 쿼리 문자열 해석. 길이를 먼저 제한하고, 같은 키가 반복되면 마지막 값을 쓴다. */
final class Query {

    private static final int MAX_LENGTH = 2048;

    private Query() {
    }

    static Map<String, String> parse(String raw) {
        Map<String, String> out = new HashMap<String, String>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        if (raw.length() > MAX_LENGTH) {
            throw AgentException.badRequest("요청 쿼리가 너무 깁니다.");
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(decode(k), decode(v));
        }
        return out;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        } catch (IllegalArgumentException e) {
            throw AgentException.badRequest("요청 쿼리 인코딩이 올바르지 않습니다.");
        }
    }
}
