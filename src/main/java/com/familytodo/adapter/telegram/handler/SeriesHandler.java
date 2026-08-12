package com.familytodo.adapter.telegram.handler;

import com.familytodo.adapter.telegram.BotRequest;
import com.familytodo.adapter.telegram.BotSender;
import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.CallbackHandler;
import com.familytodo.adapter.telegram.CommandHandler;
import com.familytodo.adapter.telegram.view.SeriesView;
import com.familytodo.application.FamilyService;
import com.familytodo.application.SeriesService;
import com.familytodo.domain.Member;
import com.familytodo.domain.TaskSeries;
import java.util.List;
import java.util.Map;
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
public class SeriesHandler implements CommandHandler, CallbackHandler {

    private final SeriesService series;
    private final FamilyService families;
    private final BotSender sender;

    public SeriesHandler(SeriesService series, FamilyService families, BotSender sender) {
        this.series = series;
        this.families = families;
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
            default -> throw new IllegalArgumentException("unknown series action " + data.action());
        }
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
