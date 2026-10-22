package com.dbagent.agent.tkprof;

import com.dbagent.agent.AgentException;
import com.dbagent.agent.AgentLog;
import com.dbagent.agent.config.AgentConfig;
import com.dbagent.agent.config.SidConfig;
import com.dbagent.agent.path.PathGuard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * tkprof 할 트레이스 파일 확인(2-A1, 설계서 3.4). 호출자는 경로를 줄 수 없고 파일 <b>이름</b>만 준다 - 에이전트가 SID 의 트레이스 폴더에 붙인다.
 *
 *   1) 이름은 영문·숫자·밑줄·하이픈 + .trc (200자 이하). 경로 구분자와 .. 은 이 규칙에서 걸러진다
 *   2) 파일 자체가 심볼릭 링크·일반 파일 아님·허용 루트 밖·읽기 불가면 거부 (PathGuard, 알럿 로그와 같은 검사)
 *   3) 실경로의 부모가 SID 의 트레이스 폴더여야 한다 (다른 폴더의 같은 이름 파일로 바꿔치기 차단)
 *   4) 크기가 agent.tkprof.max-input-mb 를 넘으면 거부 (413)
 *   5) <b>notBefore 이후에 만들어졌거나 수정된 파일</b>만 허용 - 임의의 오래된 트레이스를 읽는 요청을 막는다
 *      (2026-10-21 트레이스 A안: 파일 이름을 앱이 v$process.tracefile 에서 조회해 넘기므로 _DBIF_ 식별자 규칙 대신 이 시각 검사를 쓴다)
 *
 * notBefore 는 앱 시계, 파일 시각은 DB 서버 시계다. 두 시계의 차이는 인증이 clock-skew-seconds 안으로 묶어 두므로 같은 값만큼 여유를 준다.
 * 읽기만 하며 파일을 바꾸지 않는다. 확인과 실제 열기 사이의 교체(TOCTOU)는 PathGuard 와 같이 수용한다(설계서 3.4 8번).
 */
public final class TraceFileResolver {

    /** 확장자 포함 200자 이하 (.trc 4자 + 이름 196자). */
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,196}\\.trc");

    private final AgentConfig cfg;
    private final PathGuard guard;

    public TraceFileResolver(AgentConfig cfg, PathGuard guard) {
        this.cfg = cfg;
        this.guard = guard;
    }

    public ResolvedTrace resolve(SidConfig sc, String fileName, long notBeforeMs) {
        if (fileName == null || !FILE_NAME.matcher(fileName).matches()) {
            throw AgentException.badRequest("fileName 이 없거나 형식이 올바르지 않습니다(영문·숫자·밑줄·하이픈과 .trc).");
        }
        Path dir = sc.getEffectiveTraceDir();
        if (dir == null) {
            throw AgentException.notFound("트레이스 폴더를 알 수 없습니다.");
        }
        Path real = guard.checkReadableFile(dir.resolve(fileName));
        try {
            if (!real.getParent().equals(dir.toRealPath())) {
                throw AgentException.forbiddenPath("트레이스 폴더 밖의 파일입니다.");
            }
            long size = Files.size(real);
            if (size > cfg.getTkprofMaxInputBytes()) {
                throw AgentException.tooLarge("트레이스 파일이 너무 큽니다(상한 " + cfg.getTkprofMaxInputBytes() / (1024L * 1024L) + "MB).");
            }
            long modified = Files.getLastModifiedTime(real).toMillis();
            if (modified + cfg.getClockSkewSeconds() * 1000L < notBeforeMs) {
                throw AgentException.forbiddenPath("지정한 시각 이후에 만들어지거나 수정된 트레이스 파일이 아닙니다.");
            }
            return new ResolvedTrace(real, size, modified);
        } catch (IOException e) {
            AgentLog.warn("trace file check failed sid=" + sc.getSid() + " error=" + e);
            throw new AgentException(500, "INTERNAL", "트레이스 파일을 확인하는 중 오류가 발생했습니다.");
        }
    }
}
