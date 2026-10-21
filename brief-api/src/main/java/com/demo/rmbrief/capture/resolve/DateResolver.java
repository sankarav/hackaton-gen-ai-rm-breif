package com.demo.rmbrief.capture.resolve;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a verbatim date phrase ("next Friday", "end of the month", "Oct 24") into a calendar date
 * relative to the meeting date. Pure and deterministic — the LLM never computes dates.
 * Ambiguous phrases resolve to the most likely date and are marked approximate for the reviewer.
 */
public final class DateResolver {

    /**
     * date is null when the phrase names no fixed date; note explains the interpretation.
     * invalid=true when the phrase looks like a date but isn't a real one or couldn't be parsed.
     */
    public record Result(LocalDate date, boolean approximate, String note, boolean invalid) {
        Result(LocalDate date, boolean approximate, String note) {
            this(date, approximate, note, false);
        }
    }

    private static final Map<String, Integer> NUMBERS = Map.ofEntries(
            Map.entry("a", 1), Map.entry("an", 1), Map.entry("one", 1), Map.entry("two", 2),
            Map.entry("three", 3), Map.entry("four", 4), Map.entry("five", 5), Map.entry("six", 6),
            Map.entry("seven", 7), Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10),
            Map.entry("eleven", 11), Map.entry("twelve", 12), Map.entry("couple", 2), Map.entry("a couple", 2));

    private static final String MONTH = "(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*";
    private static final String WEEKDAY = "(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)";

    private static final Pattern ISO = Pattern.compile("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b");
    private static final Pattern SLASH = Pattern.compile("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2}|\\d{4}))?\\b");
    private static final Pattern MONTH_DAY = Pattern.compile("\\b" + MONTH + "\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:\\s+(\\d{4}))?\\b");
    private static final Pattern DAY_MONTH = Pattern.compile("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?" + MONTH + "(?:\\s+(\\d{4}))?\\b");
    private static final Pattern RELATIVE = Pattern.compile("\\b(?:in|within)\\s+(?:the\\s+next\\s+)?(\\d+|a couple|[a-z]+)\\s+(?:of\\s+)?(day|week|month)s?\\b|\\b(\\d+|[a-z]+)\\s+(day|week|month)s?\\s+from\\s+(?:now|today)\\b");
    private static final Pattern WEEKDAY_P = Pattern.compile("\\b(this|next|coming|on)?\\s*" + WEEKDAY + "\\b");
    private static final Pattern QUARTER_N = Pattern.compile("\\bq([1-4])\\b");

    private DateResolver() {}

    public static Result resolve(String phrase, LocalDate anchor) {
        String p = phrase.toLowerCase(Locale.ROOT).replaceAll("[,.]", " ").replaceAll("\\s+", " ").trim();
        Matcher m;

        if ((m = ISO.matcher(p)).find()) {
            return exact(safeDate(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))), phrase);
        }
        if ((m = SLASH.matcher(p)).find()) {
            int month = Integer.parseInt(m.group(1));
            int day = Integer.parseInt(m.group(2));
            Integer year = m.group(3) == null ? null : normalizeYear(Integer.parseInt(m.group(3)));
            return exact(forwardDate(month, day, year, anchor), phrase);
        }
        if ((m = MONTH_DAY.matcher(p)).find()) {
            return exact(forwardDate(month(m.group(1)), Integer.parseInt(m.group(2)),
                    m.group(3) == null ? null : Integer.parseInt(m.group(3)), anchor), phrase);
        }
        if ((m = DAY_MONTH.matcher(p)).find()) {
            return exact(forwardDate(month(m.group(2)), Integer.parseInt(m.group(1)),
                    m.group(3) == null ? null : Integer.parseInt(m.group(3)), anchor), phrase);
        }

        if (p.matches(".*\\b(today|tonight|eod|end of (the )?day|this afternoon|this evening)\\b.*")) return new Result(anchor, false, null);
        if (p.contains("day after tomorrow")) return new Result(anchor.plusDays(2), false, null);
        if (p.matches(".*\\btomorrow\\b.*")) return new Result(anchor.plusDays(1), false, null);

        if ((m = RELATIVE.matcher(p)).find()) {
            String n = m.group(1) != null ? m.group(1) : m.group(3);
            String unit = m.group(2) != null ? m.group(2) : m.group(4);
            Integer amount = n.matches("\\d+") ? Integer.valueOf(n) : NUMBERS.get(n);
            if (amount != null) {
                LocalDate d = switch (unit) {
                    case "day" -> anchor.plusDays(amount);
                    case "week" -> anchor.plusWeeks(amount);
                    default -> anchor.plusMonths(amount);
                };
                boolean approx = !unit.equals("day") || p.startsWith("within");
                return new Result(d, approx, approx ? "“" + phrase + "” taken as " + d : null);
            }
        }

        if (p.matches(".*\\bnext week\\b.*")) {
            return approx(fridayOnOrAfter(startOfNextWeek(anchor)), phrase);
        }
        if (p.matches(".*\\b(end of (the )?week|eow)\\b.*")) {
            return new Result(fridayOnOrAfter(anchor), false, null);
        }
        if (p.matches(".*\\bthis week\\b.*")) {
            return approx(fridayOnOrAfter(anchor), phrase);
        }
        if (p.matches(".*\\bend of next month\\b.*") || p.matches(".*\\bnext month\\b.*")) {
            return approx(anchor.plusMonths(1).with(TemporalAdjusters.lastDayOfMonth()), phrase);
        }
        if (p.matches(".*\\b(end of (the )?month|month end|month-end|eom)\\b.*")) {
            return new Result(anchor.with(TemporalAdjusters.lastDayOfMonth()), false, null);
        }
        if (p.matches(".*\\bthis month\\b.*")) {
            return approx(anchor.with(TemporalAdjusters.lastDayOfMonth()), phrase);
        }
        if ((m = QUARTER_N.matcher(p)).find()) {
            int q = Integer.parseInt(m.group(1));
            LocalDate end = LocalDate.of(anchor.getYear(), q * 3, 1).with(TemporalAdjusters.lastDayOfMonth());
            if (end.isBefore(anchor)) end = end.plusYears(1).with(TemporalAdjusters.lastDayOfMonth());
            return approx(end, phrase);
        }
        if (p.matches(".*\\b(end of (the )?quarter|quarter end|quarter-end|eoq|this quarter)\\b.*")) {
            int lastMonthOfQuarter = ((anchor.getMonthValue() - 1) / 3 + 1) * 3;
            LocalDate end = LocalDate.of(anchor.getYear(), lastMonthOfQuarter, 1).with(TemporalAdjusters.lastDayOfMonth());
            return p.contains("this quarter") ? approx(end, phrase) : new Result(end, false, null);
        }
        if (p.matches(".*\\b(end of (the )?year|year end|year-end|eoy|this year)\\b.*")) {
            LocalDate end = LocalDate.of(anchor.getYear(), 12, 31);
            return p.contains("this year") ? approx(end, phrase) : new Result(end, false, null);
        }

        if ((m = WEEKDAY_P.matcher(p)).find()) {
            DayOfWeek dow = weekday(m.group(2));
            LocalDate upcoming = anchor.with(TemporalAdjusters.next(dow));
            if ("next".equals(m.group(1))) {
                // "next Friday" is ambiguous in English: resolve to the one in the following week and flag it.
                LocalDate following = startOfNextWeek(anchor).with(TemporalAdjusters.nextOrSame(dow));
                String name = dow.getDisplayName(TextStyle.FULL, Locale.US);
                return new Result(following, true,
                        "“" + phrase + "” could mean " + upcoming + " or " + following + " — taken as " + following + " (" + name + ")");
            }
            return new Result(upcoming, false, null);
        }

        if (p.matches("^(once|when|after|upon|as soon as|if|whenever)\\b.*")) {
            return new Result(null, false, "Depends on an event (“" + phrase + "”) — no fixed date");
        }
        if (p.matches(".*\\b(asap|soon|shortly|later)\\b.*")) {
            return new Result(null, false, "No specific date given (“" + phrase + "”) — set one if needed");
        }
        return new Result(null, false, "Couldn't turn “" + phrase + "” into a date — set it manually", true);
    }

    private static Result exact(LocalDate d, String phrase) {
        return d == null ? new Result(null, false, "“" + phrase + "” isn't a valid calendar date", true) : new Result(d, false, null);
    }

    private static Result approx(LocalDate d, String phrase) {
        return new Result(d, true, "“" + phrase + "” taken as " + d);
    }

    private static LocalDate forwardDate(int month, int day, Integer year, LocalDate anchor) {
        if (year != null) return safeDate(year, month, day);
        LocalDate d = safeDate(anchor.getYear(), month, day);
        if (d != null && d.isBefore(anchor)) d = safeDate(anchor.getYear() + 1, month, day);
        return d;
    }

    private static LocalDate safeDate(int y, int m, int d) {
        try {
            return LocalDate.of(y, m, d);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static int normalizeYear(int y) {
        return y < 100 ? 2000 + y : y;
    }

    private static LocalDate startOfNextWeek(LocalDate anchor) {
        return anchor.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
    }

    private static LocalDate fridayOnOrAfter(LocalDate d) {
        return d.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
    }

    private static int month(String token) {
        String t = token.substring(0, 3);
        for (Month mo : Month.values()) {
            if (mo.getDisplayName(TextStyle.SHORT, Locale.US).toLowerCase(Locale.ROOT).equals(t)) return mo.getValue();
        }
        throw new IllegalArgumentException(token);
    }

    private static DayOfWeek weekday(String token) {
        String t = token.substring(0, 3);
        for (DayOfWeek d : DayOfWeek.values()) {
            if (d.getDisplayName(TextStyle.SHORT, Locale.US).toLowerCase(Locale.ROOT).equals(t)) return d;
        }
        throw new IllegalArgumentException(token);
    }
}
