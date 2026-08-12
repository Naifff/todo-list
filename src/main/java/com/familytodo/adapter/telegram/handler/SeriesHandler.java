package com.familytodo.adapter.telegram.handler;

import com.familytodo.adapter.telegram.BotRequest;
import com.familytodo.adapter.telegram.BotSender;
import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.CallbackHandler;
import com.familytodo.adapter.telegram.CommandHandler;
import com.familytodo.adapter.telegram.DialogHandler;
import com.familytodo.adapter.telegram.DialogState;
import com.familytodo.adapter.telegram.DialogStateStore;
import com.familytodo.adapter.telegram.view.SeriesView;
import com.familytodo.adapter.telegram.view.Texts;
import com.familytodo.application.DueDateParser;
import com.familytodo.application.FamilyService;
import com.familytodo.application.SeriesService;
import com.familytodo.domain.Member;
import com.familytodo.domain.TaskSeries;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

/**
 * Повторяющиеся дела: {@code /series}.
 *
 * <p>Экран появился из вопроса «как удалить дело, которое циклическое?». Ответа не было: удаление
 * вхождения стирает одну строку, а джоба материализации возвращает её в течение часа. Распорядиться
 * можно только правилом, и добраться до него человеку было неоткуда.
 *
 * <p>Список живёт одним сообщением: нажатие переписывает его карточкой, «← К списку» возвращает
 * обратно. Тот же приём, что в списках дел и покупок.
 */
@Component
public class SeriesHandler implements CommandHandler, CallbackHandler, DialogHandler {

    private final SeriesService series;
    private final FamilyService families;
    private final DueDateParser dates;
    private final DialogStateStore dialogs;
    private final BotSender sender;

    public SeriesHandler(
            SeriesService series,
            FamilyService families,
            DueDateParser dates,
            DialogStateStore dialogs,
            BotSender sender) {
        this.series = series;
        this.families = families;
        this.dates = dates;
        this.dialogs = dialogs;
        this.sender = sender;
    }

    @Override
    public Set<String> commands() {
        return Set.of("series");
    }

    @Override
    public String prefix() {
        return SeriesView.PREFIX;
    }

    /** Оба интерфейса объявляют метод по умолчанию — Java требует снять неоднозначность явно. */
    @Override
    public boolean allowsStrangers() {
        return false;
    }

    @Override
    public void handle(BotRequest request) {
        Member viewer = request.requireMember();
        List<TaskSeries> active = series.active(viewer);
        sender.send(
                request.chatId(),
                SeriesView.list(active, roster(viewer)),
                SeriesView.listKeyboard(active));
    }

    @Override
    public void handle(BotRequest request, CallbackData data) {
        Member viewer = request.requireMember();

        switch (data.action()) {
            case SeriesView.OPEN -> showCard(request, viewer, data.longArgument());
            case SeriesView.BACK -> showList(request, viewer);
            case SeriesView.STOP -> askToStop(request, viewer, data.longArgument());
            case SeriesView.STOP_OK -> stop(request, viewer, data.longArgument());
            case SeriesView.END -> askForEnd(request, viewer, data.longArgument());
            case SeriesView.ENDLESS -> setEnd(request, viewer, data.longArgument(), null);
            default -> throw new IllegalArgumentException("unknown series action " + data.action());
        }
    }

    /** Правило грузится и здесь: спрашивать дату для чужой серии не нужно вовсе. */
    private void askForEnd(BotRequest request, Member viewer, long seriesId) {
        TaskSeries rule = series.require(viewer, seriesId);
        dialogs.put(request.telegramUserId(), new DialogState.AwaitingSeriesEnd(rule.id()));
        sender.send(request.chatId(), Texts.SERIES_ASK_END);
    }

    /**
     * Свободный текст принимаем только внутри начатого сценария — бот сам спросил, значит ждёт.
     *
     * <p>Непонятая дата <b>не обрывает</b> сценарий: иначе после опечатки пришлось бы заново
     * открывать серию и снова жать кнопку.
     */
    @Override
    public boolean continueDialog(BotRequest request) {
        Optional<DialogState.AwaitingSeriesEnd> awaiting =
                dialogs.get(request.telegramUserId())
                        .filter(DialogState.AwaitingSeriesEnd.class::isInstance)
                        .map(DialogState.AwaitingSeriesEnd.class::cast);
        if (awaiting.isEmpty()) {
            return false;
        }

        Member viewer = request.requireMember();
        Optional<LocalDate> date =
                dates.parseDate(request.text(), families.family(viewer).timezone());
        if (date.isEmpty()) {
            sender.send(request.chatId(), Texts.SERIES_END_NOT_PARSED);
            return true;
        }

        try {
            setEnd(request, viewer, awaiting.get().seriesId(), date.get());
        } catch (IllegalArgumentException e) {
            // ⚠️ только класс исключения: в тексте лежит то, что ввёл человек
            sender.send(request.chatId(), Texts.SERIES_END_BEFORE_START);
            return true;
        }
        dialogs.clear(request.telegramUserId());
        return true;
    }

    private void setEnd(BotRequest request, Member viewer, long seriesId, LocalDate endsOn) {
        SeriesService.Changed changed = series.endBy(viewer, seriesId, endsOn);
        TaskSeries rule = changed.series();
        String text = SeriesView.card(rule, roster(viewer)) + SeriesView.limited(changed.removed());

        // ⚠️ карточка после ввода даты уходит новым сообщением, а не правкой: между ней и ответом
        // человека вклинились подсказка и его собственный текст, и старое сообщение уехало вверх
        if (request.messageId().isPresent() && endsOn == null) {
            sender.edit(
                    request.chatId(),
                    request.messageId().get(),
                    text,
                    SeriesView.cardKeyboard(rule));
        } else {
            sender.send(request.chatId(), text, SeriesView.cardKeyboard(rule));
        }
    }

    /** Спрашиваем, а не гасим сразу: будущие дела уйдут у всей семьи, а вернуть их нечем. */
    private void askToStop(BotRequest request, Member viewer, long seriesId) {
        TaskSeries rule = series.require(viewer, seriesId);
        edit(
                request,
                SeriesView.stopConfirmation(rule, roster(viewer)),
                SeriesView.stopKeyboard(rule));
    }

    private void stop(BotRequest request, Member viewer, long seriesId) {
        SeriesService.Changed stopped = series.stop(viewer, seriesId);
        List<TaskSeries> active = series.active(viewer);
        edit(
                request,
                SeriesView.stopped(stopped.removed()) + "\n\n" + SeriesView.list(active, roster(viewer)),
                SeriesView.listKeyboard(active));
    }

    /**
     * ⚠️ Правило грузится заново и с проверкой видимости: {@code callback_data} недоверенный, кнопки
     * отражают права, но не обеспечивают их. Чужой номер обязан выглядеть как несуществующий.
     */
    private void showCard(BotRequest request, Member viewer, long seriesId) {
        TaskSeries rule = series.require(viewer, seriesId);
        edit(request, SeriesView.card(rule, roster(viewer)), SeriesView.cardKeyboard(rule));
    }

    private void showList(BotRequest request, Member viewer) {
        List<TaskSeries> active = series.active(viewer);
        edit(
                request,
                SeriesView.list(active, roster(viewer)),
                SeriesView.listKeyboard(active));
    }

    private void edit(BotRequest request, String text, InlineKeyboardMarkup keyboard) {
        request.messageId()
                .ifPresentOrElse(
                        id -> sender.edit(request.chatId(), id, text, keyboard),
                        () -> sender.send(request.chatId(), text, keyboard));
    }

    private Map<Long, Member> roster(Member viewer) {
        return families.roster(viewer).stream()
                .collect(Collectors.toMap(Member::id, Function.identity()));
    }
}
