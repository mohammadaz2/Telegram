package org.telegram.messenger.time;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

import java.text.FieldPosition;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link FastDateFormat} that optionally renders the date part of a pattern in the Solar Hijri
 * (Shamsi / Jalali) calendar.
 *
 * <p>When the preference {@code shamsi_enabled} in the main preferences is {@code false} (the
 * default) every method delegates to {@link FastDateFormat}, so the app behaves exactly like the
 * stock client. When the preference is {@code true} the date fields of the pattern
 * ({@code y}/{@code Y}, {@code M}/{@code L}, {@code d}) are rendered using the Jalali calendar,
 * while literals and all time / zone / weekday fields are still rendered by the stock
 * {@link FastDateFormat} (weekday names are identical in both calendars for a given instant).
 *
 * <p>Month names are localized only when the formatter locale is Persian ({@code fa*}):
 * {@code فروردین..اسفند}; for any other locale the English transliteration
 * ({@code Farvardin..Esfand}) is used. Numbers are always emitted with Latin digits.
 *
 * <p>This class never throws: any failure inside the Jalali rendering falls back to the stock
 * {@link FastDateFormat} output for the same pattern.
 */
public class ShamsiFastDateFormat extends FastDateFormat {

    /** Preference key in the "mainconfig" shared preferences. Read every time, default false. */
    public static final String PREF_KEY = "shamsi_enabled";

    private static final String MAIN_PREFS = "mainconfig";

    private static volatile Boolean sShamsiEnabledCached = null;

    private static final ConcurrentHashMap<String, ShamsiFastDateFormat> sInstanceCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Part[]> sPatternCache = new ConcurrentHashMap<>();

    private static final String[] MONTHS_FA = {
            "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
            "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    };
    private static final String[] MONTHS_FA_SHORT = {
            "فرو", "ارد", "خرد", "تیر", "مرد", "شهر",
            "مهر", "آبا", "آذر", "دی", "بهم", "اسف"
    };
    private static final String[] MONTHS_EN = {
            "Farvardin", "Ordibehesht", "Khordad", "Tir", "Mordad", "Shahrivar",
            "Mehr", "Aban", "Azar", "Dey", "Bahman", "Esfand"
    };
    private static final String[] MONTHS_EN_SHORT = {
            "Far", "Ord", "Kho", "Tir", "Mor", "Sha",
            "Meh", "Aba", "Aza", "Dey", "Bah", "Esf"
    };

    private final String pattern;
    private final Locale locale;
    private final TimeZone timeZone;

    private ShamsiFastDateFormat(String pattern, TimeZone timeZone, Locale locale) {
        super(pattern, timeZone, locale);
        this.pattern = pattern;
        this.timeZone = timeZone;
        this.locale = locale;
    }

    // ------------------------------------------------------------------------------------
    // Factory / preference helpers
    // ------------------------------------------------------------------------------------

    /**
     * Returns a (cached) formatter for the given pattern and locale. Behaves like
     * {@link FastDateFormat#getInstance(String, Locale)} but renders Jalali dates when the
     * preference is enabled.
     */
    public static FastDateFormat getInstance(String pattern, Locale locale) {
        final Locale l = locale == null ? Locale.getDefault() : locale;
        final TimeZone tz = TimeZone.getDefault();
        final String key = pattern + '\u0000' + l.toString() + '\u0000' + tz.getID();
        ShamsiFastDateFormat formatter = sInstanceCache.get(key);
        if (formatter == null) {
            formatter = new ShamsiFastDateFormat(pattern, tz, l);
            final ShamsiFastDateFormat previous = sInstanceCache.putIfAbsent(key, formatter);
            if (previous != null) {
                formatter = previous;
            }
        }
        return formatter;
    }

    public static boolean isShamsiEnabled() {
        Boolean cached = sShamsiEnabledCached;
        if (cached != null) {
            return cached;
        }
        try {
            final Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return false;
            }
            final SharedPreferences prefs = context.getSharedPreferences(MAIN_PREFS, Context.MODE_PRIVATE);
            boolean val = prefs.getBoolean(PREF_KEY, false);
            sShamsiEnabledCached = val;
            return val;
        } catch (Throwable ignore) {
            return false;
        }
    }

    public static void setShamsiEnabled(boolean enabled) {
        sShamsiEnabledCached = enabled;
        try {
            final Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return;
            }
            context.getSharedPreferences(MAIN_PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(PREF_KEY, enabled).apply();
        } catch (Throwable ignore) {
        }
    }

    /**
     * Converts an instant to the Solar Hijri calendar using the default time zone.
     *
     * @return array of {year, month (1..12), day (1..31)}
     */
    public static int[] getJalaliDate(long millis) {
        final Calendar calendar = Calendar.getInstance(TimeZone.getDefault());
        calendar.setTimeInMillis(millis);
        return gregorianToJalali(
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH));
    }

    // ------------------------------------------------------------------------------------
    // FastDateFormat overrides
    // ------------------------------------------------------------------------------------

    @Override
    public StringBuffer format(Object obj, StringBuffer toAppendTo, FieldPosition pos) {
        if (!isShamsiEnabled()) {
            return super.format(obj, toAppendTo, pos);
        }
        try {
            final Long millis = toMillis(obj);
            if (millis != null) {
                return formatJalali(millis, toAppendTo);
            }
        } catch (Throwable ignore) {
        }
        return super.format(obj, toAppendTo, pos);
    }

    @Override
    public String format(long millis) {
        if (!isShamsiEnabled()) {
            return super.format(millis);
        }
        try {
            return formatJalali(millis, new StringBuffer()).toString();
        } catch (Throwable ignore) {
            return super.format(millis);
        }
    }

    @Override
    public String format(Date date) {
        if (!isShamsiEnabled()) {
            return super.format(date);
        }
        try {
            return formatJalali(date.getTime(), new StringBuffer()).toString();
        } catch (Throwable ignore) {
            return super.format(date);
        }
    }

    @Override
    public String format(Calendar calendar) {
        if (!isShamsiEnabled()) {
            return super.format(calendar);
        }
        try {
            return formatJalali(calendar.getTimeInMillis(), new StringBuffer()).toString();
        } catch (Throwable ignore) {
            return super.format(calendar);
        }
    }

    @Override
    public StringBuffer format(long millis, StringBuffer buf) {
        if (!isShamsiEnabled()) {
            return super.format(millis, buf);
        }
        try {
            return formatJalali(millis, buf);
        } catch (Throwable ignore) {
            return super.format(millis, buf);
        }
    }

    @Override
    public StringBuffer format(Date date, StringBuffer buf) {
        if (!isShamsiEnabled()) {
            return super.format(date, buf);
        }
        try {
            return formatJalali(date.getTime(), buf);
        } catch (Throwable ignore) {
            return super.format(date, buf);
        }
    }

    @Override
    public StringBuffer format(Calendar calendar, StringBuffer buf) {
        if (!isShamsiEnabled()) {
            return super.format(calendar, buf);
        }
        try {
            return formatJalali(calendar.getTimeInMillis(), buf);
        } catch (Throwable ignore) {
            return super.format(calendar, buf);
        }
    }

    private static Long toMillis(Object obj) {
        if (obj instanceof Date) {
            return ((Date) obj).getTime();
        }
        if (obj instanceof Calendar) {
            return ((Calendar) obj).getTimeInMillis();
        }
        if (obj instanceof Number) {
            return ((Number) obj).longValue();
        }
        return null;
    }

    // ------------------------------------------------------------------------------------
    // Jalali rendering
    // ------------------------------------------------------------------------------------

    private StringBuffer formatJalali(long millis, StringBuffer buf) {
        final Calendar calendar = Calendar.getInstance(timeZone);
        calendar.setTimeInMillis(millis);
        final int[] jalali = gregorianToJalali(
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH));
        final int jalaliYear = jalali[0];
        final int jalaliMonth = jalali[1];
        final int jalaliDay = jalali[2];
        final Part[] parts = getPatternParts(pattern);
        final boolean persian = isPersianLocale(locale);
        for (final Part part : parts) {
            if (!part.patternLetters) {
                buf.append(part.text);
                continue;
            }
            final char first = part.text.charAt(0);
            final int count = part.text.length();
            switch (first) {
                case 'y':
                case 'Y':
                    appendYear(buf, jalaliYear, count);
                    break;
                case 'M':
                case 'L':
                    appendMonth(buf, jalaliMonth, count, persian);
                    break;
                case 'd':
                    appendNumber(buf, jalaliDay, count <= 1 ? 0 : count);
                    break;
                default:
                    // Weekday / time / timezone / era fields are identical in both calendars.
                    FastDateFormat.getInstance(part.text, timeZone, locale).format(millis, buf);
                    break;
            }
        }
        return buf;
    }

    private static void appendYear(StringBuffer buf, int year, int count) {
        if (count == 2) {
            int twoDigits = year % 100;
            if (twoDigits < 0) {
                twoDigits += 100;
            }
            appendNumber(buf, twoDigits, 2);
        } else {
            appendNumber(buf, year, count <= 1 ? 0 : count);
        }
    }

    private static void appendMonth(StringBuffer buf, int month, int count, boolean persian) {
        if (month < 1 || month > 12) {
            appendNumber(buf, month, count <= 1 ? 0 : count);
            return;
        }
        if (count >= 4) {
            buf.append((persian ? MONTHS_FA : MONTHS_EN)[month - 1]);
        } else if (count == 3) {
            buf.append((persian ? MONTHS_FA_SHORT : MONTHS_EN_SHORT)[month - 1]);
        } else {
            appendNumber(buf, month, count == 2 ? 2 : 0);
        }
    }

    private static void appendNumber(StringBuffer buf, int value, int width) {
        final String text = Integer.toString(value);
        for (int a = text.length(); a < width; a++) {
            buf.append('0');
        }
        buf.append(text);
    }

    private static boolean isPersianLocale(Locale locale) {
        final String language = locale.getLanguage();
        return language != null && language.toLowerCase(Locale.US).startsWith("fa");
    }

    private static Part[] getPatternParts(String pattern) {
        Part[] parts = sPatternCache.get(pattern);
        if (parts == null) {
            parts = parsePattern(pattern);
            final Part[] previous = sPatternCache.putIfAbsent(pattern, parts);
            if (previous != null) {
                parts = previous;
            }
        }
        return parts;
    }

    /**
     * Splits a {@link java.text.SimpleDateFormat} pattern into literal chunks and runs of the same
     * pattern letter. Quoted literals are unquoted, doubled apostrophes become a single one.
     */
    private static Part[] parsePattern(String pattern) {
        final ArrayList<Part> parts = new ArrayList<>();
        final StringBuilder literal = new StringBuilder();
        final int length = pattern.length();
        int i = 0;
        while (i < length) {
            final char c = pattern.charAt(i);
            if (c == '\'') {
                i++;
                if (i < length && pattern.charAt(i) == '\'') {
                    literal.append('\'');
                    i++;
                    continue;
                }
                while (i < length) {
                    final char quoted = pattern.charAt(i);
                    if (quoted == '\'') {
                        if (i + 1 < length && pattern.charAt(i + 1) == '\'') {
                            literal.append('\'');
                            i += 2;
                        } else {
                            i++;
                            break;
                        }
                    } else {
                        literal.append(quoted);
                        i++;
                    }
                }
            } else if (isAsciiLetter(c)) {
                if (literal.length() > 0) {
                    parts.add(new Part(literal.toString(), false));
                    literal.setLength(0);
                }
                final int start = i;
                while (i < length && pattern.charAt(i) == c) {
                    i++;
                }
                parts.add(new Part(pattern.substring(start, i), true));
            } else {
                literal.append(c);
                i++;
            }
        }
        if (literal.length() > 0) {
            parts.add(new Part(literal.toString(), false));
        }
        return parts.toArray(new Part[0]);
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static final class Part {
        final String text;
        final boolean patternLetters;

        Part(String text, boolean patternLetters) {
            this.text = text;
            this.patternLetters = patternLetters;
        }
    }

    // ------------------------------------------------------------------------------------
    // Gregorian -> Jalali conversion (standard jalaali algorithm, no external libraries)
    // ------------------------------------------------------------------------------------

    /**
     * Converts a Gregorian date to the Solar Hijri calendar.
     *
     * @return array of {year, month (1..12), day (1..31)}
     */
    private static int[] gregorianToJalali(int gy, int gm, int gd) {
        final int[] gDaysInMonth = {0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334};
        int gy2 = gm > 2 ? gy + 1 : gy;
        long days = 355666L
                + 365L * gy
                + (gy2 + 3) / 4
                - (gy2 + 99) / 100
                + (gy2 + 399) / 400
                + gd
                + gDaysInMonth[gm - 1];
        int jy = -1595 + (int) (33 * (days / 12053L));
        days %= 12053L;
        jy += 4 * (int) (days / 1461L);
        days %= 1461L;
        if (days > 365) {
            jy += (int) ((days - 1) / 365L);
            days = (days - 1) % 365L;
        }
        final int jm;
        final int jd;
        if (days < 186) {
            jm = 1 + (int) (days / 31L);
            jd = 1 + (int) (days % 31L);
        } else {
            jm = 7 + (int) ((days - 186) / 30L);
            jd = 1 + (int) ((days - 186) % 30L);
        }
        return new int[]{jy, jm, jd};
    }
}
