package com.familytodo.application;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор сроков и правила времени.
 *
 * <p>Живёт в слое юзкейсов, а не в адаптере бота: тихие часы и время по умолчанию —
 * это политика продукта, и на неё опирается ещё и планировщик напоминаний.
 *
 * <p>Всё считается в таймзоне семьи и хранится моментом. Часы приходят бином: {@code Instant.now()}
 * в коде означал бы, что границу суток и переход на летнее время проверить нечем.
 */
public class DueDateParser {

    /**
     * Время по умолчанию для дат без времени.
     *
     * <p>Полночь сделала бы задачу просроченной в момент создания, 23:59 будило бы семью ночью.
     * 19:00 — вечер, когда дело ещё можно сделать.
     */
    public static final LocalTime DEFAULT_TIME = LocalTime.of(19, 0);

    /** Тихие часы: напоминание, попавшее в окно, переносится на утро. */
    public static final LocalTime QUIET_START = LocalTime.of(22, 0);

    public static final LocalTime QUIET_END = LocalTime.of(8, 0);

    private static final Pattern DATE_AND_TIME =
            Pattern.compile(
                    "^(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{4}))?(?:\\s+(\\d{1,2}):(\\d{2}))?$");
    private static final Pattern TIME_ONLY = Pattern.compile("^(\\d{1,2}):(\\d{2})$");

    private final Clock clock;

    public DueDateParser(Clock clock) {
        this.clock = clock;
    }

    public Instant today(ZoneId zone) {
        return at(LocalDate.now(clock.withZone(zone)), DEFAULT_TIME, zone);
    }

    public Instant tomorrow(ZoneId zone) {
        return at(LocalDate.now(clock.withZone(zone)).plusDays(1), DEFAULT_TIME, zone);
    }

    /** В субботу и воскресенье выходные уже идут — переносить на следующие незачем. */
    public Instant weekend(ZoneId zone) {
        LocalDate date = LocalDate.now(clock.withZone(zone));
        while (date.getDayOfWeek() != DayOfWeek.SATURDAY
                && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
            date = date.plusDays(1);
        }
        return at(date, DEFAULT_TIME, zone);
    }

    /** Пустой результат — не сбой, а «не разобрал»: ввод пользовательский и бывает любым. */
    public Optional<Instant> parse(String input, ZoneId zone) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String trimmed = input.trim();

        Matcher timeOnly = TIME_ONLY.matcher(trimmed);
        if (timeOnly.matches()) {
            return parseTimeOnly(timeOnly, zone);
        }

        Matcher dateAndTime = DATE_AND_TIME.matcher(trimmed);
        if (dateAndTime.matches()) {
            return parseDate(dateAndTime, zone);
        }

        return Optional.empty();
    }

    /**
     * Когда действительно слать напоминание.
     *
     * <p>Срок остаётся тем, что задал человек, — сдвигается только уведомление. Попавшее в тихие
     * часы уезжает на 08:00: разбудить всю семью «вынеси мусор» в половине двенадцатого ночи хуже,
     * чем напомнить утром.
     */
    public Instant reminderTimeFor(Instant dueAt, ZoneId zone) {
        ZonedDateTime local = dueAt.atZone(zone);
        LocalTime time = local.toLocalTime();

        if (!time.isBefore(QUIET_START)) {
            return at(local.toLocalDate().plusDays(1), QUIET_END, zone);
        }
        if (time.isBefore(QUIET_END)) {
            return at(local.toLocalDate(), QUIET_END, zone);
        }
        return dueAt;
    }

    private Optional<Instant> parseTimeOnly(Matcher matcher, ZoneId zone) {
        Optional<LocalTime> time = time(matcher.group(1), matcher.group(2));
        if (time.isEmpty()) {
            return Optional.empty();
        }

        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        LocalDate date = now.toLocalDate();
        // время, которое уже прошло, значит завтра: иначе задача просрочена сразу
        if (!time.get().isAfter(now.toLocalTime())) {
            date = date.plusDays(1);
        }
        return Optional.of(at(date, time.get(), zone));
    }

    private Optional<Instant> parseDate(Matcher matcher, ZoneId zone) {
        int day = Integer.parseInt(matcher.group(1));
        int month = Integer.parseInt(matcher.group(2));

        LocalTime time = DEFAULT_TIME;
        if (matcher.group(4) != null) {
            Optional<LocalTime> parsed = time(matcher.group(4), matcher.group(5));
            if (parsed.isEmpty()) {
                return Optional.empty();
            }
            time = parsed.get();
        }

        LocalDate today = LocalDate.now(clock.withZone(zone));
        int year = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : today.getYear();

        LocalDate date;
        try {
            date = LocalDate.of(year, month, day);
        } catch (java.time.DateTimeException e) {
            return Optional.empty();
        }

        // дата без года в прошлом означает следующий год, а не просрочку годичной давности
        if (matcher.group(3) == null && date.isBefore(today)) {
            date = date.plusYears(1);
        }
        return Optional.of(at(date, time, zone));
    }

    private static Optional<LocalTime> time(String hours, String minutes) {
        try {
            return Optional.of(LocalTime.of(Integer.parseInt(hours), Integer.parseInt(minutes)));
        } catch (java.time.DateTimeException e) {
            return Optional.empty();
        }
    }

    /**
     * {@code ZonedDateTime.of} сам разрешает несуществующий час при переходе на летнее время,
     * сдвигая его вперёд на длительность перехода. Нам этого достаточно: срок в такой час —
     * редкость, а падать на нём нельзя.
     */
    private static Instant at(LocalDate date, LocalTime time, ZoneId zone) {
        return ZonedDateTime.of(LocalDateTime.of(date, time), zone).toInstant();
    }
}
