package com.familytodo.adapter.telegram.view;

import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.TaskRef;
import com.familytodo.domain.Member;
import com.familytodo.domain.Task;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * Расписание на горизонт: дела, сгруппированные по дням.
 *
 * <p>Вёрстка повторяет бумажный список из макетов: заголовок дня, под ним дела по времени. Сетку
 * Telegram не рисует — в сообщении нет ни таблиц, ни цвета, ни позиционирования.
 */
public final class AgendaView {

    public static final String PREFIX = "a";
    public static final String DAYS = "days";

    /**
     * Расписание списком — тем же HTML-файлом, но без сетки.
     *
     * <p>Занял место картинки. У сетки день упирается в ось и её границы, у списка границ нет
     * вовсе; какой вид удобнее, зависит от дня и от человека, поэтому выбирает он.
     */
    public static final String LIST = "list";

    /**
     * История — то же расписание, но назад: {@code PAST} сеткой, {@code PAST_LIST} списком.
     *
     * <p>Отдельным действием, а не отрицательным горизонтом: «минус семь» в {@code callback_data}
     * пришлось бы разбирать и проверять на каждом шаге, а смысл у выборки другой — там все статусы,
     * здесь только открытые.
     *
     * <p>⚠️ Форм стало две. Раньше история приходила только списком: считалось, что за месяц сетка
     * нечитаема. Это верно про <b>ось часов</b>, но сетка за месяц — не ось, а раскладка неделями,
     * и она читается прекрасно.
     */
    public static final String PAST = "past";

    public static final String PAST_LIST = "plist";

    /** Назад заглядывают неделей или месяцем; день истории — это «вчера», за ним не ходят. */
    public static final List<Integer> PAST_HORIZONS = List.of(7, 30);

    /**
     * Расписание страницей — HTML-файлом.
     *
     * <p>Второй вид рядом с картинкой, а не вместо неё: картинка обзорнее и открывается в один тап,
     * страница подробнее и листается. Какой из них удобнее на настоящем телефоне, решает не спор, а
     * пользование.
     */
    public static final String PAGE = "page";

    /** Горизонты из макетов. */
    public static final List<Integer> HORIZONS = List.of(1, 3, 7, 30);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final Locale RU_LOCALE = Locale.of("ru");
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMMM", RU_LOCALE);

    private static final int FOOTER_RESERVE = 60;

    /** Аргумент кнопки со страницей: {@code 7p2} — неделя, третья страница. */
    private static final Pattern PAGED = Pattern.compile("(\\d{1,3})p(\\d{1,3})");

    private AgendaView() {}

    /**
     * @param shown дела этой страницы — по ним строятся номерные кнопки
     * @param from номер первого дела страницы в общем списке, с нуля
     * @param page номер страницы с нуля, {@code pages} — сколько их всего
     */
    public record Rendered(String text, List<Task> shown, int from, int page, int pages) {}

    /**
     * Аргумент кнопки: горизонт и страница в одном поле.
     *
     * <p>Отдельного действия для страницы нет намеренно — {@link #DAYS} принимает обе формы, и
     * старая кнопка из одного числа (страница нулевая) продолжает работать. Кнопки в чате живут
     * вечно, а сообщение, отправленное до появления страниц, обязано открывать расписание.
     */
    public static String argument(int days, int page) {
        return page == 0 ? Integer.toString(days) : days + "p" + page;
    }

    public static int horizonOf(String argument) {
        Matcher paged = PAGED.matcher(argument);
        return Integer.parseInt(paged.matches() ? paged.group(1) : argument);
    }

    /** Страница из недоверенного аргумента. Всё, что не похоже на страницу, — первая. */
    public static int pageOf(String argument) {
        Matcher paged = PAGED.matcher(argument);
        return paged.matches() ? Integer.parseInt(paged.group(2)) : 0;
    }

    public static Rendered render(
            List<Task> dated,
            List<Task> undated,
            Map<Long, Member> byId,
            ZoneId zone,
            Instant now,
            int days,
            int page) {

        // ⚠️ дела без срока идут теми же страницами и той же нумерацией. Прежде они нумеровались,
        // а датированные нет — в одном сообщении жили два правила, и номер на кнопке не с чем было
        // сличить глазами
        List<Task> all = new ArrayList<>(dated);
        all.addAll(undated);
        if (all.isEmpty()) {
            return new Rendered(Texts.AGENDA_EMPTY, List.of(), 0, 0, 1);
        }

        int pages = TaskListView.pagesFor(all.size());
        int current = Math.clamp(page, 0, pages - 1);
        int from = current * TaskListView.PAGE_SIZE;
        int to = Math.min(all.size(), from + TaskListView.PAGE_SIZE);

        StringBuilder out = new StringBuilder("<b>").append(header(days));
        if (pages > 1) {
            out.append(" · стр. ").append(current + 1).append(" из ").append(pages);
        }
        out.append("</b>\n");

        int budget = HtmlEscaper.MESSAGE_LIMIT - FOOTER_RESERVE;
        List<Task> shown = new ArrayList<>();
        LocalDate currentDay = null;
        boolean undatedHeaderWritten = false;

        for (int i = from; i < to; i++) {
            Task task = all.get(i);
            StringBuilder chunk = new StringBuilder();

            if (momentOf(task) == null) {
                if (!undatedHeaderWritten) {
                    chunk.append("\n\n<b>Без срока</b>");
                }
                chunk.append('\n').append(i + 1).append(". ").append(HtmlEscaper.escape(task.title()));
            } else {
                LocalDate day = momentOf(task).atZone(zone).toLocalDate();
                if (!day.equals(currentDay)) {
                    chunk.append('\n').append(dayHeader(day, LocalDate.ofInstant(now, zone)));
                }
                chunk.append('\n').append(i + 1).append(". ").append(line(task, byId, zone, now));
            }

            // страховка: при десяти строках бюджет кончиться не может, но обрезанная страница
            // означала бы недостижимые дела — ровно то, ради чего страницы и заводились
            if (out.length() + chunk.length() > budget) {
                break;
            }
            out.append(chunk);
            if (momentOf(task) == null) {
                undatedHeaderWritten = true;
            } else {
                currentDay = momentOf(task).atZone(zone).toLocalDate();
            }
            shown.add(task);
        }

        return new Rendered(out.toString(), shown, from, current, pages);
    }

    /** Кнопки под расписанием: сначала переключение горизонта, потом номера дел. */
    public static InlineKeyboardMarkup keyboard(Rendered rendered, int days) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        InlineKeyboardRow horizons = new InlineKeyboardRow();
        for (int horizon : HORIZONS) {
            horizons.add(
                    InlineKeyboardButton.builder()
                            .text(horizon == days ? "· " + label(horizon) + " ·" : label(horizon))
                            .callbackData(
                                    new CallbackData(PREFIX, DAYS, Integer.toString(horizon))
                                            .serialize())
                            .build());
        }
        rows.add(horizons);

        InlineKeyboardRow views = new InlineKeyboardRow();
        views.add(
                InlineKeyboardButton.builder()
                        .text("Сеткой")
                        .callbackData(
                                new CallbackData(PREFIX, PAGE, Integer.toString(days)).serialize())
                        .build());
        views.add(
                InlineKeyboardButton.builder()
                        .text("Списком")
                        .callbackData(
                                new CallbackData(PREFIX, LIST, Integer.toString(days)).serialize())
                        .build());
        rows.add(views);

        // по строке на горизонт: форма выбирается тем же одним нажатием, что и вперёд,
        // а не отдельным экраном «сеткой или списком?»
        for (int horizon : PAST_HORIZONS) {
            String name = horizon == 7 ? "← Неделя" : "← Месяц";
            rows.add(
                    new InlineKeyboardRow(
                            pastButton(name + " сеткой", PAST, horizon),
                            pastButton(name + " списком", PAST_LIST, horizon)));
        }

        InlineKeyboardRow row = new InlineKeyboardRow();
        List<Task> shown = rendered.shown();
        for (int i = 0; i < shown.size(); i++) {
            // ⚠️ подпись повторяет номер строки, и номер сквозной: «1» на второй странице
            // отправляла бы искать первое дело списка
            row.add(
                    InlineKeyboardButton.builder()
                            .text(Integer.toString(rendered.from() + i + 1))
                            .callbackData(
                                    new CallbackData(
                                                    TaskCardView.PREFIX,
                                                    TaskCardView.CARD,
                                                    // ⚠️ ссылка несёт горизонт: «← Назад» из
                                                    // карточки обязан вернуть в то же расписание,
                                                    // а не в список всех дел
                                                    TaskRef.forAgenda(days, shown.get(i).id()))
                                            .serialize())
                            .build());
            if (row.size() == 5) {
                rows.add(row);
                row = new InlineKeyboardRow();
            }
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }

        // ⚠️ стрелка появляется, только если ей есть куда вести: кнопка, которая ничего не делает,
        // читается как сломанная — ответить «дальше некуда» отсюда нечем
        InlineKeyboardRow nav = new InlineKeyboardRow();
        if (rendered.page() > 0) {
            nav.add(pageButton("◀ Назад", days, rendered.page() - 1));
        }
        if (rendered.page() < rendered.pages() - 1) {
            nav.add(pageButton("Вперёд ▶", days, rendered.page() + 1));
        }
        if (!nav.isEmpty()) {
            rows.add(nav);
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static InlineKeyboardButton pageButton(String label, int days, int page) {
        return InlineKeyboardButton.builder()
                .text(label)
                .callbackData(new CallbackData(PREFIX, DAYS, argument(days, page)).serialize())
                .build();
    }

    private static String line(Task task, Map<Long, Member> byId, ZoneId zone, Instant now) {
        StringBuilder line = new StringBuilder();
        line.append(time(task, zone)).append("  ");
        line.append(HtmlEscaper.escape(task.title()));

        if (task.location() != null) {
            line.append(" — ").append(HtmlEscaper.escape(task.location()));
        }
        line.append("  ·  ").append(AssigneeNames.of(task, byId));
        return line.toString();
    }

    /** У интервала показываем оба конца, у срока — «к 19:00»: это разные обещания. */
    private static String time(Task task, ZoneId zone) {
        if (task.isScheduled()) {
            String start = task.startsAt().atZone(zone).format(TIME);
            return task.endsAt() == null
                    ? start
                    : start + "–" + task.endsAt().atZone(zone).format(TIME);
        }
        return "к " + task.dueAt().atZone(zone).format(TIME);
    }

    private static String dayHeader(LocalDate day, LocalDate today) {
        String weekday = day.getDayOfWeek().getDisplayName(TextStyle.FULL, RU_LOCALE);
        String prefix =
                day.equals(today) ? "сегодня, " : day.equals(today.plusDays(1)) ? "завтра, " : "";
        return "\n<b>" + prefix + day.format(DAY) + ", " + weekday + "</b>";
    }

    /** Момент дела: у события начало, у срока — срок. У дела без даты его нет вовсе. */
    private static Instant momentOf(Task task) {
        return task.isScheduled() ? task.startsAt() : task.dueAt();
    }

    private static String name(Map<Long, Member> byId, long memberId) {
        Member member = byId.get(memberId);
        return HtmlEscaper.escape(member == null ? "кто-то" : member.displayName());
    }

    /**
     * Подпись к картинке. Дел без даты на календаре нет — им негде быть на оси времени, — и
     * умолчать об этом нельзя: человек решит, что у него их не осталось.
     */
    private static InlineKeyboardButton pastButton(String label, String action, int horizon) {
        return InlineKeyboardButton.builder()
                .text(label)
                .callbackData(
                        new CallbackData(PREFIX, action, Integer.toString(horizon)).serialize())
                .build();
    }

    public static String historyCaption(int days) {
        return "<b>Что было за " + (days == 7 ? "неделю" : "месяц") + "</b>";
    }

    public static String caption(int days, int undated) {
        String caption = "<b>" + header(days) + "</b>";
        return undated == 0 ? caption : caption + "\n" + undated + " " + undatedWord(undated) + " без даты — в списке";
    }

    private static String undatedWord(int count) {
        int last = count % 10;
        boolean teen = count % 100 >= 11 && count % 100 <= 14;
        if (!teen && last == 1) {
            return "дело";
        }
        return !teen && last >= 2 && last <= 4 ? "дела" : "дел";
    }

    private static String header(int days) {
        return "Расписание · " + label(days);
    }

    private static String label(int days) {
        return switch (days) {
            case 1 -> "день";
            case 3 -> "3 дня";
            case 7 -> "неделя";
            default -> days + " дней";
        };
    }
}
