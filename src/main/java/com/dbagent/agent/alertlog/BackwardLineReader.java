package com.dbagent.agent.alertlog;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * 파일 끝에서 앞으로 한 줄씩 읽는다(1-A5). 알럿 로그는 수 GB 일 수 있어 전체를 읽지 않고 64KB 블록 단위로 거꾸로 읽는다.
 *
 * 줄 구분은 바이트 0x0A 로 찾는다(UTF-8·ASCII·EUC-KR 등 ASCII 호환 인코딩에서 안전, UTF-16 은 지원하지 않음).
 * 문자 디코딩은 줄 하나가 완성된 뒤에 하므로 블록 경계에서 멀티바이트 문자가 잘리지 않는다.
 * 읽은 바이트가 maxScanBytes 에 닿으면 멈추고 limitReached() 가 true 가 된다("다 보지 못했음").
 */
final class BackwardLineReader implements Closeable {

    private static final int BLOCK = 64 * 1024;
    /** 줄바꿈 없이 이어지는 구간의 상한. 이보다 길면 비정상(바이너리 등)으로 보고 멈춘다. */
    private static final int MAX_PARTIAL_LINE = 1024 * 1024;

    private final FileChannel file;
    private final long fileSize;
    private final long maxScanBytes;
    private final long deadlineNanos;
    private long pos;                       // 아직 읽지 않은 구간의 끝(배타)
    private long scanned;
    private byte[] carry;                   // 앞부분이 아직 읽히지 않은 줄의 뒷부분
    private boolean limitReached;
    private final ArrayDeque<byte[]> ready = new ArrayDeque<byte[]>();   // 완성된 줄, 맨 앞이 파일의 가장 뒤쪽 줄

    /**
     * @param maxScanMillis 읽기 시간 상한(설계서 3.6 "스캔 바이트·시간 상한"). 넘으면 바이트 상한과 같이 limitReached.
     */
    BackwardLineReader(Path path, long maxScanBytes, long maxScanMillis) throws IOException {
        this.file = open(path);
        boolean ok = false;
        try {
            this.fileSize = file.size();
            this.maxScanBytes = maxScanBytes;
            this.deadlineNanos = System.nanoTime() + maxScanMillis * 1000000L;
            this.pos = fileSize;
            if (pos > 0) {
                ByteBuffer one = ByteBuffer.allocate(1);
                if (file.read(one, pos - 1) == 1 && one.get(0) == '\n') {
                    pos--;      // 파일 끝의 줄바꿈은 줄 구분일 뿐 빈 줄이 아니다
                }
            }
            ok = true;
        } finally {
            if (!ok) {
                file.close();
            }
        }
    }

    /**
     * PathGuard 확인 뒤 열기 전에 파일이 심볼릭 링크로 바뀌는 경쟁(TOCTOU)을 줄이려고 마지막 경로 요소의 링크를 따라가지 않고 연다
     * (Unix 에서는 O_NOFOLLOW). 이 옵션을 지원하지 않는 JVM·OS 면 UnsupportedOperationException 이므로 일반 열기로 물러선다 -
     * 그 경우 위험은 설계서 3.4 8번 전제(디렉터리 쓰기는 oracle 계정만)로 수용한 범위와 같다.
     */
    private static FileChannel open(Path path) throws IOException {
        try {
            return FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException e) {
            return FileChannel.open(path, StandardOpenOption.READ);
        }
    }

    long fileSize() {
        return fileSize;
    }

    long scannedBytes() {
        return scanned;
    }

    boolean limitReached() {
        return limitReached;
    }

    /** @return 다음(더 앞쪽) 줄의 바이트(끝의 CR 제거), 파일 처음이거나 읽기 상한에 닿았으면 null */
    byte[] nextLine() throws IOException {
        while (ready.isEmpty()) {
            if (limitReached || pos == 0) {
                return null;        // 완성된 줄을 모두 돌려준 뒤에만 여기에 온다
            }
            if (scanned >= maxScanBytes || System.nanoTime() - deadlineNanos > 0) {
                limitReached = true;
                return null;
            }
            readBlock();
        }
        return stripCr(ready.pollFirst());
    }

    private void readBlock() throws IOException {
        int n = (int) Math.min(BLOCK, pos);
        long start = pos - n;
        byte[] data = new byte[n + (carry == null ? 0 : carry.length)];
        ByteBuffer buf = ByteBuffer.wrap(data, 0, n);
        while (buf.hasRemaining()) {
            // 열 때 잰 크기까지만 읽는다. 그 사이 파일이 잘렸으면(DBA 의 정리) 끝을 만나므로 오류로 알린다("못 읽음").
            if (file.read(buf, start + buf.position()) < 0) {
                throw new EOFException("alert log shrank while reading");
            }
        }
        if (carry != null) {
            System.arraycopy(carry, 0, data, n, carry.length);
        }
        pos = start;
        scanned += n;

        int lineEnd = data.length;
        for (int i = data.length - 1; i >= 0; i--) {
            if (data[i] == '\n') {
                ready.addLast(Arrays.copyOfRange(data, i + 1, lineEnd));
                lineEnd = i;
            }
        }
        if (pos == 0) {
            ready.addLast(Arrays.copyOfRange(data, 0, lineEnd));       // 파일의 첫 줄
            carry = null;
        } else if (lineEnd > MAX_PARTIAL_LINE) {
            limitReached = true;
            carry = null;
        } else {
            carry = Arrays.copyOfRange(data, 0, lineEnd);
        }
    }

    private static byte[] stripCr(byte[] b) {
        if (b.length > 0 && b[b.length - 1] == '\r') {
            return Arrays.copyOf(b, b.length - 1);
        }
        return b;
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
