package com.dbagent.agent.tkprof;

import java.nio.file.Path;

/** 검사를 모두 통과한 트레이스 파일. tkprof 에는 이 실경로만 넘긴다(2-A2). */
public final class ResolvedTrace {

    private final Path file;
    private final long sizeBytes;
    private final long modifiedMs;

    ResolvedTrace(Path file, long sizeBytes, long modifiedMs) {
        this.file = file;
        this.sizeBytes = sizeBytes;
        this.modifiedMs = modifiedMs;
    }

    public Path getFile() {
        return file;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public long getModifiedMs() {
        return modifiedMs;
    }
}
