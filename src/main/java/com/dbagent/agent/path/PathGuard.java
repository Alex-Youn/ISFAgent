package com.dbagent.agent.path;

import com.dbagent.agent.AgentException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/**
 * 경로 방어(1-A7, 설계서 3.4). 호출자는 경로를 줄 수 없고(sid -> 설정의 경로), 설정된 경로도 매 요청마다 다시 확인한다.
 *
 *   1) 파일 자체가 심볼릭 링크면 거부 (알럿 로그를 다른 파일로 바꿔치기하는 우회 차단)
 *   2) 일반 파일이 아니면 거부 (디렉터리·장치 파일)
 *   3) 실경로(toRealPath - 중간 디렉터리 링크까지 풀린 경로)가 기동 때 고정한 허용 루트 밑이어야 한다
 *   4) 읽을 수 없으면 거부
 *
 * 확인과 실제 열기 사이에 파일이 바뀌는 경쟁(TOCTOU)은 디렉터리 쓰기 권한이 oracle 계정에만 있다는 전제로 수용한다(설계서 3.4 8번).
 * 서버 로그에는 사정을 남기되, 호출자에게는 경로를 알려 주지 않는다.
 */
public final class PathGuard {

    private final List<Path> realRoots;

    public PathGuard(List<Path> realRoots) {
        this.realRoots = realRoots;
    }

    /** @return 읽어도 되는 실경로 */
    public Path checkReadableFile(Path configured) {
        if (!Files.exists(configured, LinkOption.NOFOLLOW_LINKS)) {
            throw AgentException.notFound("파일이 없습니다.");
        }
        if (Files.isSymbolicLink(configured)) {
            throw AgentException.forbiddenPath("허용되지 않은 경로입니다(심볼릭 링크).");
        }
        if (!Files.isRegularFile(configured, LinkOption.NOFOLLOW_LINKS)) {
            throw AgentException.forbiddenPath("일반 파일이 아닙니다.");
        }
        Path real;
        try {
            real = configured.toRealPath();
        } catch (IOException e) {
            throw AgentException.forbiddenPath("경로를 확인할 수 없습니다.");
        }
        if (!underRoot(real)) {
            throw AgentException.forbiddenPath("허용된 폴더 밖의 경로입니다.");
        }
        if (!Files.isReadable(real)) {
            throw AgentException.forbiddenPath("읽기 권한이 없습니다.");
        }
        return real;
    }

    /** health 용: 예외 없이 읽을 수 있는지만 본다. */
    public boolean isReadable(Path configured) {
        try {
            checkReadableFile(configured);
            return true;
        } catch (AgentException e) {
            return false;
        }
    }

    private boolean underRoot(Path real) {
        for (Path r : realRoots) {
            if (real.startsWith(r)) {
                return true;
            }
        }
        return false;
    }
}
