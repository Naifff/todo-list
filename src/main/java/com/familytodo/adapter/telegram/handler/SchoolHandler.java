package com.familytodo.adapter.telegram.handler;

import com.familytodo.adapter.telegram.BotRequest;
import com.familytodo.adapter.telegram.BotSender;
import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.CallbackHandler;
import com.familytodo.adapter.telegram.CommandHandler;
import com.familytodo.adapter.telegram.DialogHandler;
import com.familytodo.adapter.telegram.DialogState;
import com.familytodo.adapter.telegram.DialogStateStore;
import com.familytodo.adapter.telegram.view.SchoolView;
import com.familytodo.adapter.telegram.view.Texts;
import com.familytodo.application.FamilyService;
import com.familytodo.application.SchoolService;
import com.familytodo.domain.DomainException;
import com.familytodo.domain.Lesson;
import com.familytodo.domain.Member;
import com.familytodo.domain.Role;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Расписание уроков: {@code /school}.
 *
 * <p>Ребёнку показывается своё сразу — выбирать ему не из чего. Родителю сперва «чьё»: расписаний в
 * семье столько же, сколько школьников.
 *
 * <p>Экран заведён вместе с юзкейсом, а не после него: в этом проекте уже был случай, когда
 * написанная и покрытая тестами остановка серии не вызывалась ни одной кнопкой, и сборка этого не
 * видела.
 */
@Component
public class SchoolHandler implements CommandHandler, CallbackHandler, DialogHandler {

    private static final Logger log = LoggerFactory.getLogger(SchoolHandler.class);

    private final SchoolService school;
    private final FamilyService families;
    private final DialogStateStore dialogs;
    private final BotSender sender;

    public SchoolHandler(
            SchoolService school,
            FamilyService families,
            DialogStateStore dialogs,
            BotSender sender) {
        this.school = school;
        this.families = families;
        this.dialogs = dialogs;
        this.sender = sender;
    }

    @Override
    public Set<String> commands() {
        return Set.of("school");
    }

    @Override
    public String prefix() {
        return SchoolView.PREFIX;
    }

    /** Оба интерфейса объявляют метод по умолчанию — Java требует снять неоднозначность явно. */
    @Override
    public boolean allowsStrangers() {
        return false;
    }

    @Override
    public void handle(BotRequest request) {
        Member viewer = request.requireMember();
        if (viewer.role() == Role.CHILD) {
            sendSchedule(request, viewer, viewer.id());
            return;
        }

        List<Member> pupils =
                families.roster(viewer).stream().filter(member -> member.role() == Role.CHILD).toList();
        if (pupils.isEmpty()) {
            sender.send(request.chatId(), Texts.SCHOOL_NO_PUPILS);
            return;
        }
        if (pupils.size() == 1) {
            // выбор из одного — лишнее нажатие на каждом заходе
            sendSchedule(request, viewer, pupils.getFirst().id());
            return;
        }
        sender.send(request.chatId(), Texts.SCHOOL_WHOSE, SchoolView.pupils(pupils));
    }

    @Override
    public void handle(BotRequest request, CallbackData data) {
        Member viewer = request.requireMember();

        switch (data.action()) {
            case SchoolView.OPEN -> editSchedule(request, viewer, data.longArgument());
            case SchoolView.REPLACE -> askForSchedule(request, viewer, data.longArgument());
            case SchoolView.CLEAR -> clear(request, viewer, data.longArgument());
            default -> throw new IllegalArgumentException("unknown school action " + data.action());
        }
    }

    /**
     * Свободный текст принимаем только внутри начатого сценария — бот сам спросил, значит ждёт.
     *
     * <p>⚠️ Непонятое сообщение <b>не обрывает</b> сценарий и не трогает прежнее расписание: иначе
     * опечатка в одной строке стирала бы учебный год.
     */
    @Override
    public boolean continueDialog(BotRequest request) {
        Optional<DialogState.AwaitingSchedule> awaiting =
                dialogs.get(request.telegramUserId())
                        .filter(DialogState.AwaitingSchedule.class::isInstance)
                        .map(DialogState.AwaitingSchedule.class::cast);
        if (awaiting.isEmpty()) {
            return false;
        }

        Member viewer = request.requireMember();
        SchoolService.Replaced replaced;
        try {
            replaced = school.replace(viewer, awaiting.get().memberId(), request.text());
        } catch (IllegalArgumentException e) {
            // ⚠️ только класс исключения: в тексте лежит то, что прислал человек
            log.warn("schedule rejected: {}", e.getClass().getSimpleName());
            sender.send(request.chatId(), Texts.SCHOOL_NOT_PARSED);
            return true;
        }

        dialogs.clear(request.telegramUserId());
        // одним сообщением, а не двумя: счёт и есть заголовок к тому, что получилось
        sendSchedule(
                request,
                viewer,
                awaiting.get().memberId(),
                "Было уроков: %d, стало: %d.".formatted(replaced.before(), replaced.after()));
        return true;
    }

    private void askForSchedule(BotRequest request, Member viewer, long memberId) {
        // ⚠️ Право — до чтения, а не после. Обратный порядок отвечал бы ребёнку на попытку править
        // расписание брата «не найдено» вместо «нельзя»: чужое расписание он и правда не видит, но
        // спрашивали не об этом. Внутри семьи существование участника не секрет.
        requireManageable(viewer, memberId);
        pupil(viewer, memberId);

        dialogs.put(request.telegramUserId(), new DialogState.AwaitingSchedule(memberId));
        sender.send(request.chatId(), Texts.SCHOOL_ASK);
    }

    private void clear(BotRequest request, Member viewer, long memberId) {
        school.clear(viewer, memberId);
        editSchedule(request, viewer, memberId);
    }

    private void sendSchedule(BotRequest request, Member viewer, long memberId) {
        sendSchedule(request, viewer, memberId, null);
    }

    private void sendSchedule(BotRequest request, Member viewer, long memberId, String notice) {
        List<Lesson> lessons = school.of(viewer, memberId);
        Member pupil = pupil(viewer, memberId);
        String text = SchoolView.render(pupil, lessons);
        sender.send(
                request.chatId(),
                notice == null ? text : notice + "\n\n" + text,
                SchoolView.keyboard(pupil, lessons));
    }

    /** Экран живёт одним сообщением: нажатие переписывает его, а не добавляет ещё одно. */
    private void editSchedule(BotRequest request, Member viewer, long memberId) {
        List<Lesson> lessons = school.of(viewer, memberId);
        Member pupil = pupil(viewer, memberId);
        String text = SchoolView.render(pupil, lessons);
        var keyboard = SchoolView.keyboard(pupil, lessons);

        request.messageId()
                .ifPresentOrElse(
                        id -> sender.edit(request.chatId(), id, text, keyboard),
                        () -> sender.send(request.chatId(), text, keyboard));
    }

    /**
     * Право на чужое расписание: родитель правит любому в семье, ребёнок — только себе. Проверка
     * повторяет юзкейс намеренно: {@code callback_data} недоверенный, а спрашивать расписание перед
     * отказом было бы лишним запросом.
     */
    private void requireManageable(Member viewer, long memberId) {
        if (viewer.role() != Role.PARENT && viewer.id() != memberId) {
            throw new DomainException.NotPermitted("actor may not manage this schedule");
        }
    }

    private Member pupil(Member viewer, long memberId) {
        return families.roster(viewer).stream()
                .filter(member -> member.id() == memberId)
                .findFirst()
                .orElseThrow(() -> new DomainException.NotFound("member not found"));
    }
}
