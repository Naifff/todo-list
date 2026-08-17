package com.familytodo.adapter.telegram.view;

import com.familytodo.domain.Lesson;
import com.familytodo.domain.Member;
import com.familytodo.domain.Task;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Утренний дайджест — с днями заголовками.
 *
 * <p>Своя вёрстка, а не {@link TaskListView}, и причин две. Кнопок под дайджестом нет, поэтому
 * нумерация строк в нём — шум: номер не на что нажать. А главное, у дайджеста другая ось: список дел
 * отвечает «что мне поручено», дайджест — «что когда». Плоский список повторял дату на каждой
 * строке, и пять дел на 13.08 читались как пять разных дней.
 *
 * <p>Дайджест <b>персональный</b>: в него попадает только то, что поручено получателю. Поэтому
 * исполнители в строках не печатаются вовсе — это всегда он сам, — а автор называется, только если
 * это кто-то другой.
 */
public final class DigestView {

    private static final Locale RU = Locale.of("ru");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM");

    /** Тот же запас под хвост сообщения, что и у списка дел: Telegram режет на 4096 символах. */
    private static final int FOOTER_RESERVE = 40;

    private DigestView() {}

    public static String render(
            String header,
            List<Task> tasks,
            List<Lesson> lessons,
            Member recipient,
            Map<Long, Member> byId,
            ZoneId zone,
            LocalDate from,
            int horizonDays,
            Instant now) {

        StringBuilder out = new StringBuilder("<b>").append(header).append("</b>\n");
        int budget = HtmlEscaper.MESSAGE_LIMIT - FOOTER_RESERVE;
        LocalDate today = LocalDate.ofInstant(now, zone);

        for (Map.Entry<LocalDate, List<Item>> day : byDay(tasks, zone).entrySet()) {
            StringBuilder group = new StringBuilder("\n<b>").append(heading(day.getKey(), today));
            group.append("</b>\n");

            for (Item item : day.getValue()) {
                group.append(line(item, recipient, byId, zone, now)).append('\n');
            }
            if (out.length() + group.length() > budget) {
                break;
            }
            out.append(group);
        }

        appendLessons(out, lessons, recipient, byId, from, budget);
        return out.toString().stripTrailing();
    }

    /**
     * Расписание — <b>своя сущность</b>, а не строки среди дел.
     *
     * <p>⚠️ Прежде уроки стояли вперемешку с делами по времени, и довод был разумный: человек читает
     * утро подряд — «в 07:30 портфель, в 08:30 математика». С телефона 18 августа стало видно, чего
     * это стоит: шесть уроков среди двух дел — это список, в котором дел не найти. Дайджест отвечает
     * «что мне сегодня делать», а урок идёт сам и в этот вопрос не входит; но знать, каким будет
     * день, всё равно нужно — отсюда отдельный блок в конце.
     *
     * <p>Блок — на <b>один</b> день, тот, с которого начинается окно: уроки приходят только в
     * дневной дайджест, см. {@code DigestJob}.
     */
    private static void appendLessons(
            StringBuilder out,
            List<Lesson> lessons,
            Member recipient,
            Map<Long, Member> byId,
            LocalDate day,
            int budget) {

        List<Lesson> ofTheDay = lessons.stream().filter(lesson -> lesson.occursOn(day)).toList();
        if (ofTheDay.isEmpty()) {
            // пустой заголовок «Уроки» читается как поломка расписания, а не как выходной
            return;
        }

        StringBuilder group = new StringBuilder("\n<b>🎒 Уроки</b>\n");
        for (Lesson lesson : ofTheDay) {
            group.append(lessonLine(lesson, recipient, byId)).append('\n');
        }
        if (out.length() + group.length() <= budget) {
            out.append(group);
        }
    }

    /** Строка дайджеста. Урок сюда не попадает: у него свой блок и своя природа. */
    private record Item(Task task) {}

    /**
     * Группировка сохраняет порядок, в котором дела пришли из выборки: он уже по моменту времени.
     * Дела без срока идут последними — {@code null} как ключ, отсюда {@link LinkedHashMap}, а не
     * сортировка ключей.
     */
    private static Map<LocalDate, List<Item>> byDay(List<Task> tasks, ZoneId zone) {
        Map<LocalDate, List<Item>> byDay = new LinkedHashMap<>();
        for (Task task : tasks) {
            byDay.computeIfAbsent(day(task, zone), key -> new java.util.ArrayList<>())
                    .add(new Item(task));
        }

        // дни идут по возрастанию, «без срока» последним: у него ключа-даты нет вовсе
        List<LocalDate> order = new java.util.ArrayList<>(byDay.keySet());
        order.sort(java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()));

        Map<LocalDate, List<Item>> sorted = new LinkedHashMap<>();
        order.forEach(day -> sorted.put(day, byDay.get(day)));
        return sorted;
    }

    private static LocalDate day(Task task, ZoneId zone) {
        Instant moment = task.isScheduled() ? task.startsAt() : task.dueAt();
        return moment == null ? null : LocalDate.ofInstant(moment, zone);
    }

    /**
     * «Сегодня» и «завтра» словами, дальше — днём недели: «15.08» не отвечает на вопрос «это суббота
     * или понедельник», а именно он и решает, влезет ли дело в день.
     */
    private static String heading(LocalDate day, LocalDate today) {
        if (day == null) {
            return "Без срока";
        }
        if (day.equals(today)) {
            return "Сегодня, " + day.format(DATE);
        }
        if (day.equals(today.plusDays(1))) {
            return "Завтра, " + day.format(DATE);
        }
        return weekday(day) + ", " + day.format(DATE);
    }

    private static String weekday(LocalDate day) {
        String name = day.getDayOfWeek().getDisplayName(TextStyle.FULL_STANDALONE, RU);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static String line(
            Item item, Member recipient, Map<Long, Member> byId, ZoneId zone, Instant now) {

        Task task = item.task();
        StringBuilder line = new StringBuilder("• ");
        if (task.dueAt() != null && task.dueAt().isBefore(now)) {
            line.append("❗️");
        }
        line.append(HtmlEscaper.escape(task.title()));

        String when = when(task, zone);
        if (!when.isEmpty()) {
            line.append(" · ").append(when);
        }
        if (task.location() != null) {
            line.append(" · ").append(HtmlEscaper.escape(task.location()));
        }
        // автор называется, только если это кто-то другой: «от Мама» в списке самой мамы — шум
        if (task.creatorId() != recipient.id()) {
            line.append(" · от ").append(AssigneeNames.of(byId, task.creatorId()));
        }
        return line.toString();
    }

    /**
     * Урок: время, предмет и — если получатель не сам школьник — чей он.
     *
     * <p>Родителю имя обязательно: своих уроков у него нет, а «математика в 08:30» без имени в семье
     * с двумя школьниками не отвечает ни на что.
     */
    private static String lessonLine(Lesson lesson, Member recipient, Map<Long, Member> byId) {
        // время впереди: внутри блока читают расписание, а в нём главное «во сколько»
        StringBuilder line =
                new StringBuilder("• ")
                        .append(lesson.startsAt().format(TIME))
                        .append('–')
                        .append(lesson.endsAt().format(TIME))
                        .append("  ")
                        .append(HtmlEscaper.escape(lesson.subject()));
        if (lesson.memberId() != recipient.id()) {
            line.append(" · ").append(AssigneeNames.of(byId, lesson.memberId()));
        }
        return line.toString();
    }

    /**
     * У дела с интервалом — интервал, у остальных — срок. Смешивать нельзя: «08:00–08:40» и «к
     * 19:00» это разные обещания. Дата не печатается: она в заголовке дня.
     */
    private static String when(Task task, ZoneId zone) {
        if (task.isScheduled()) {
            ZonedDateTime start = task.startsAt().atZone(zone);
            return task.endsAt() == null
                    ? start.format(TIME)
                    : start.format(TIME) + "–" + task.endsAt().atZone(zone).format(TIME);
        }
        return task.dueAt() == null ? "" : "к " + task.dueAt().atZone(zone).format(TIME);
    }
}
