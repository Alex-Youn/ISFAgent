package com.dbagent.agent;

/**
 * 요청 처리 중 호출자에게 돌려줄 오류(1-A8). HTTP 상태 + 오류 코드 + 한국어 설명으로 응답한다.
 * 설계서 3.2의 코드: AUTH_FAILED(401) FORBIDDEN_PATH(403) NOT_FOUND(404) BAD_REQUEST(400) TOO_LARGE(413) TIMEOUT(504) BUSY(429).
 * 구현하며 두 개를 더했다: METHOD_NOT_ALLOWED(405), INTERNAL(500).
 */
public final class AgentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;

    public AgentException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static AgentException badRequest(String message) {
        return new AgentException(400, "BAD_REQUEST", message);
    }

    public static AgentException notFound(String message) {
        return new AgentException(404, "NOT_FOUND", message);
    }

    public static AgentException forbiddenPath(String message) {
        return new AgentException(403, "FORBIDDEN_PATH", message);
    }

    /** 이유를 구분해 알려 주지 않는다(어느 쪽이 틀렸는지 공격자에게 알리지 않음). 이유는 서버 로그에만 남긴다. */
    public static AgentException authFailed() {
        return new AgentException(401, "AUTH_FAILED", "인증에 실패했습니다.");
    }

    public static AgentException tooLarge(String message) {
        return new AgentException(413, "TOO_LARGE", message);
    }
}
