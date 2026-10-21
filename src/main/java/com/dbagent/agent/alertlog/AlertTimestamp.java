package com.dbagent.agent.alertlog;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 알럿 로그 타임스탬프 줄 판별·변환(1-A6, 설계서 6.3). 두 형식을 모두 받는다.
 *   11g·12.1 : Mon Oct 20 10:00:00 2026            (시간대 없음 = DB 서버 로컬 시각 -> 설정한 시간대로 해석)
 *   12.2 이상 : 2026-10-20T10:00:00.123456+09:00   (오프셋 포함)
 * 줄 전체가 타임스탬프일 때만 인정한다 - 본문에 날짜가 섞인 줄을 항목 경계로 오인하지 않게.
 */
final class AlertTimestamp {

    private static final int MAX_LEN = 40;

    private static final Pattern LEGACY = Pattern.compile(
            "(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) +(\\d{1,2}) (\\d{2}):(\\d{2}):(\\d{2}) (\\d{4})");
    private static final Pattern ISO = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})");
    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    private AlertTimestamp() {
    }

    /** @return epoch ms, 타임스탬프 줄이 아니면 null */
    static Long parse(String line, ZoneId legacyZone) {
        int len = line.length();
        if (len < 19 || len > MAX_LEN) {
            return null;        // 대부분의 본문 줄은 여기서 바로 걸러진다
        }
        char c = line.charAt(0);
        try {
            if (c >= '0' && c <= '9') {
                if (!ISO.matcher(line).matches()) {
                    return null;
                }
                return OffsetDateTime.parse(line).toInstant().toEpochMilli();
            }
            Matcher m = LEGACY.matcher(line);
            if (!m.matches()) {
                return null;
            }
            int month = monthIndex(m.group(1));
            return LocalDateTime.of(Integer.parseInt(m.group(6)), month, Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)))
                    .atZone(legacyZone).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        } catch (DateTimeException e) {
            return null;        // 2월 30일 같은 값
        }
    }

    private static int monthIndex(String abbr) {
        for (int i = 0; i < MONTHS.length; i++) {
            if (MONTHS[i].equals(abbr)) {
                return i + 1;
            }
        }
        throw new DateTimeException("month " + abbr);
    }
}
