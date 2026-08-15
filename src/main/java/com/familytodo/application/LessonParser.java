package com.familytodo.application;

import com.familytodo.domain.Lesson;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Расписание уроков, написанное одним сообщением.
 *
 * <p>Живёт в слое юзкейсов, а не в адаптере бота: это язык ввода продукта, а не особенность
 * Telegram.
 *
 * <p>Ввод — главный риск фичи. Экран, на котором тридцать уроков заводятся по одному, до
 * пользователя не доходит: это уже проверено кнопкой «Нескольким…», до которой не добрались ни разу.
 * Поэтому расписание пишется целиком, как список покупок, и принимается <b>целиком или отвергается
 * целиком</b>: «добавлено 27» при тридцати строках оставляет человека гадать, какие три пропали.
 *
 * <p>⚠️ Звонки нигде не хранятся. Они живут в самом сообщении и нужны только чтобы расставить
 * предметы по позициям; дальше уезжает уже конкретное время. Так не появляется ни второй таблицы, ни
 * экрана настроек — ценой того, что сообщение обязано нести звонки, если пользуется сеткой.
 */
public class LessonParser {

    /** Сколько идёт урок, если звонок назван одним временем. Школьное умолчание. */
    public static final Duration DEFAULT_LENGTH = Duration.ofMinutes(45);

    /** Прочерк в позиции — окно: звонок пропускается, а не сдвигает остальные. */
    private static final String WINDOW = "-";

    /**
     * ⚠️ {@code CASE_INSENSITIVE} без {@code UNICODE_CASE} работает только для латиницы: «Звонки» с
     * большой буквы не совпало бы с образцом, строка уехала бы в разбор дня и отвергла сообщение
     * целиком. Ошибка тихая — образец выглядит правильным.
     */
    private static final int RU = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern BELLS = Pattern.compile("^звонки\\s*:\\s*(.+)$", RU);
    private static final Pattern UNTIL = Pattern.compile("^до\\s*:\\s*(.+)$", RU);
    private static final Pattern DAY_LINE = Pattern.compile("^([\\p{L}]+)\\s*:\\s*(.+)$");
    private static final Pattern EXPLICIT =
            Pattern.compile("^([\\p{L}]+)\\s+(\\d{1,2}):(\\d{2})(?:\\s*-\\s*(\\d{1,2}):(\\d{2}))?\\s+(.+)$");
    private static final Pattern BELL =
            Pattern.compile("^(\\d{1,2}):(\\d{2})(?:\\s*-\\s*(\\d{1,2}):(\\d{2}))?$");
    private static final Pattern DATE = Pattern.compile("^(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{4}))?$");

    private static final Map<String, DayOfWeek> DAYS = days();

    /** Урок без принадлежности: кому и в какой семье, знает юзкейс. */
    public record Parsed(DayOfWeek day, LocalTime startsAt, LocalTime endsAt, String subject) {}

    /** @param validTo последний день действия; {@code null} — без конца */
    public record Schedule(List<Parsed> lessons, LocalDate validTo) {}

    /**
     * Пустой результат — «не разобрал», а не сбой: ввод пользовательский и бывает любым. Причина
     * наружу не выносится намеренно, ответ один на все случаи — как у списка покупок.
     *
     * @param validFrom первый день действия: от него отсчитывается «До: 31.05» без года
     */
    public Optional<Schedule> parse(String text, LocalDate validFrom) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                lines.add(line.strip());
            }
        }

        // Первый проход — звонки и граница. Порядок строк в сообщении не должен иметь значения:
        // человек допишет «Звонки:» в конец, и это не повод отвергать расписание целиком.
        List<Bell> bells = null;
        LocalDate validTo = null;
        for (String line : lines) {
            Matcher bellsLine = BELLS.matcher(line);
            if (bellsLine.matches()) {
                Optional<List<Bell>> parsed = bells(bellsLine.group(1));
                if (parsed.isEmpty()) {
                    return Optional.empty();
                }
                bells = parsed.get();
                continue;
            }
            Matcher untilLine = UNTIL.matcher(line);
            if (untilLine.matches()) {
                Optional<LocalDate> parsed = date(untilLine.group(1).strip(), validFrom);
                if (parsed.isEmpty()) {
                    return Optional.empty();
                }
                validTo = parsed.get();
            }
        }

        List<Parsed> lessons = new ArrayList<>();
        for (String line : lines) {
            if (BELLS.matcher(line).matches() || UNTIL.matcher(line).matches()) {
                continue;
            }

            Optional<List<Parsed>> parsed = lesson(line, bells);
            if (parsed.isEmpty()) {
                return Optional.empty();
            }
            lessons.addAll(parsed.get());
        }

        if (lessons.isEmpty()) {
            return Optional.empty();
        }
        lessons.sort(Comparator.comparing(Parsed::day).thenComparing(Parsed::startsAt));
        return Optional.of(new Schedule(List.copyOf(lessons), validTo));
    }

    /** Строка расписания: либо день с явным временем, либо день с предметами по звонкам. */
    private Optional<List<Parsed>> lesson(String line, List<Bell> bells) {
        Matcher explicit = EXPLICIT.matcher(line);
        if (explicit.matches()) {
            DayOfWeek day = DAYS.get(normalise(explicit.group(1)));
            Optional<LocalTime> start = time(explicit.group(2), explicit.group(3));
            if (day == null || start.isEmpty()) {
                return Optional.empty();
            }

            LocalTime end;
            if (explicit.group(4) != null) {
                Optional<LocalTime> parsed = time(explicit.group(4), explicit.group(5));
                if (parsed.isEmpty()) {
                    return Optional.empty();
                }
                end = parsed.get();
            } else {
                end = start.get().plus(DEFAULT_LENGTH);
            }
            return one(day, start.get(), end, explicit.group(6));
        }

        Matcher dayLine = DAY_LINE.matcher(line);
        if (dayLine.matches()) {
            DayOfWeek day = DAYS.get(normalise(dayLine.group(1)));
            // ⚠️ сетка без звонков неразрешима, и молчать об этом нельзя: строка выглядит
            // осмысленной, а разложить её не на что
            if (day == null || bells == null) {
                return Optional.empty();
            }
            return grid(day, dayLine.group(2), bells);
        }
        return Optional.empty();
    }

    private Optional<List<Parsed>> grid(DayOfWeek day, String subjects, List<Bell> bells) {
        String[] parts = subjects.split(",", -1);
        if (parts.length > bells.size()) {
            // молчаливая обрезка означала бы потерянный урок, о котором никто не узнает
            return Optional.empty();
        }

        List<Parsed> lessons = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            String subject = parts[i].strip();
            if (subject.equals(WINDOW)) {
                continue;
            }
            Optional<List<Parsed>> parsed =
                    one(day, bells.get(i).startsAt(), bells.get(i).endsAt(), subject);
            if (parsed.isEmpty()) {
                return Optional.empty();
            }
            lessons.addAll(parsed.get());
        }
        return Optional.of(lessons);
    }

    /**
     * Проверка предмета доверена домену: два набора правил разошлись бы, и разбор пропускал бы то,
     * чего не примет {@link Lesson}.
     */
    private Optional<List<Parsed>> one(DayOfWeek day, LocalTime start, LocalTime end, String subject) {
        if (subject.isBlank() || subject.length() > Lesson.MAX_SUBJECT_LENGTH) {
            return Optional.empty();
        }
        if (!end.isAfter(start)) {
            return Optional.empty();
        }
        return Optional.of(List.of(new Parsed(day, start, end, subject.strip())));
    }

    private Optional<List<Bell>> bells(String written) {
        List<Bell> bells = new ArrayList<>();
        for (String part : written.split(",", -1)) {
            Matcher bell = BELL.matcher(part.strip());
            if (!bell.matches()) {
                return Optional.empty();
            }
            Optional<LocalTime> start = time(bell.group(1), bell.group(2));
            if (start.isEmpty()) {
                return Optional.empty();
            }

            LocalTime end;
            if (bell.group(3) != null) {
                Optional<LocalTime> parsed = time(bell.group(3), bell.group(4));
                if (parsed.isEmpty()) {
                    return Optional.empty();
                }
                end = parsed.get();
            } else {
                end = start.get().plus(DEFAULT_LENGTH);
            }
            if (!end.isAfter(start.get())) {
                return Optional.empty();
            }
            bells.add(new Bell(start.get(), end));
        }
        return bells.isEmpty() ? Optional.empty() : Optional.of(bells);
    }

    /** Дата без года — ближайшая будущая, тем же правилом, что и у сроков дел. */
    private Optional<LocalDate> date(String written, LocalDate from) {
        Matcher matcher = DATE.matcher(written);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            int year =
                    matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : from.getYear();
            LocalDate parsed =
                    LocalDate.of(year, Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(1)));
            return Optional.of(
                    matcher.group(3) == null && parsed.isBefore(from) ? parsed.plusYears(1) : parsed);
        } catch (java.time.DateTimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<LocalTime> time(String hours, String minutes) {
        try {
            return Optional.of(LocalTime.of(Integer.parseInt(hours), Integer.parseInt(minutes)));
        } catch (java.time.DateTimeException e) {
            return Optional.empty();
        }
    }

    private static String normalise(String written) {
        return written.toLowerCase(Locale.of("ru")).replace('ё', 'е');
    }

    /**
     * Короткие и полные названия — оба: человек пишет как привык, а переучивать его ради разбора
     * значит потерять ввод.
     */
    private static Map<String, DayOfWeek> days() {
        Map<String, DayOfWeek> days = new LinkedHashMap<>();
        days.put("пн", DayOfWeek.MONDAY);
        days.put("понедельник", DayOfWeek.MONDAY);
        days.put("вт", DayOfWeek.TUESDAY);
        days.put("вторник", DayOfWeek.TUESDAY);
        days.put("ср", DayOfWeek.WEDNESDAY);
        days.put("среда", DayOfWeek.WEDNESDAY);
        days.put("чт", DayOfWeek.THURSDAY);
        days.put("четверг", DayOfWeek.THURSDAY);
        days.put("пт", DayOfWeek.FRIDAY);
        days.put("пятница", DayOfWeek.FRIDAY);
        days.put("сб", DayOfWeek.SATURDAY);
        days.put("суббота", DayOfWeek.SATURDAY);
        days.put("вс", DayOfWeek.SUNDAY);
        days.put("воскресенье", DayOfWeek.SUNDAY);
        return Map.copyOf(days);
    }

    private record Bell(LocalTime startsAt, LocalTime endsAt) {}
}
