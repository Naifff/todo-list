package com.familytodo.application;

import com.familytodo.application.port.FamilyRepository;
import com.familytodo.application.port.MemberRepository;
import com.familytodo.application.port.Notifier;
import com.familytodo.application.port.TaskRepository;
import com.familytodo.domain.Actor;
import com.familytodo.domain.DomainException;
import com.familytodo.domain.Family;
import com.familytodo.domain.Member;
import com.familytodo.domain.Role;
import com.familytodo.domain.Task;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/** Юзкейсы состава семьи: регистрация, исключение участника, смена роли и настроек. */
public class FamilyService {

    /**
     * Причина, которую видит автор задачи, когда исполнителя исключили. Текст системный и в
     * логах не появляется — в лог уходит только id задачи.
     */
    public static final String REMOVED_MEMBER_REASON = "участник больше не в семье";

    private final FamilyRepository families;
    private final MemberRepository members;
    private final TaskRepository tasks;
    private final Notifier notifier;
    private final Clock clock;

    public FamilyService(
            FamilyRepository families,
            MemberRepository members,
            TaskRepository tasks,
            Notifier notifier,
            Clock clock) {
        this.families = families;
        this.members = members;
        this.tasks = tasks;
        this.notifier = notifier;
        this.clock = clock;
    }

    /**
     * Создатель семьи получает роль PARENT — отдельного признака администратора нет. Роль и есть
     * право управлять составом, вторая колонка означала бы второе место, где это право можно
     * потерять.
     */
    public Member createFamily(
            long telegramUserId, long chatId, String displayName, String familyName, ZoneId zone) {
        members.findByTelegramUserId(telegramUserId)
                .ifPresent(
                        existing -> {
                            throw new DomainException.NotPermitted("person already in a family");
                        });

        Instant now = clock.instant();
        Family family = families.save(Family.create(families.nextId(), familyName, zone, now));
        return members.save(
                Member.join(
                        members.nextId(),
                        family.id(),
                        telegramUserId,
                        chatId,
                        displayName,
                        Role.PARENT,
                        now));
    }

    /**
     * Исключение: статус участника меняется на REMOVED, а его открытые задачи закрываются от имени
     * системы. Задачи, которые он поручил другим, не трогаются — делать их всё ещё нужно.
     */
    public void removeMember(Member actor, long targetMemberId) {
        Family family = family(actor);
        Member target = member(actor.familyId(), targetMemberId);

        family.removeMember(actor.asActor(), target, members.findActive(actor.familyId()));
        members.save(target);

        cancelOpenTasksOf(target);
    }

    public void changeRole(Member actor, long targetMemberId, Role newRole) {
        Family family = family(actor);
        Member target = member(actor.familyId(), targetMemberId);

        family.changeRole(actor.asActor(), target, newRole, members.findActive(actor.familyId()));
        members.save(target);
    }

    public Family changeTimezone(Member actor, ZoneId zone) {
        Family family = family(actor);
        family.changeTimezone(actor.asActor(), zone);
        return families.save(family);
    }

    public Family changeDigestTime(Member actor, java.time.LocalTime digestTime) {
        Family family = family(actor);
        family.changeDigestTime(actor.asActor(), digestTime);
        return families.save(family);
    }

    public Family changeDigestHorizon(Member actor, int days) {
        Family family = family(actor);
        family.changeDigestHorizon(actor.asActor(), days);
        return families.save(family);
    }

    public List<Member> roster(Member viewer) {
        return members.findActive(viewer.familyId());
    }

    public Family family(Member member) {
        return families.findById(member.familyId())
                .orElseThrow(() -> new DomainException.NotFound("family not found"));
    }

    private void cancelOpenTasksOf(Member removed) {
        Instant now = clock.instant();
        List<Task> open = tasks.find(TaskQuery.openAssignedTo(removed.familyId(), removed.id()));

        for (Task task : open) {
            task.cancelBySystem(Actor.system(), REMOVED_MEMBER_REASON, now);
            Task saved = tasks.save(task);

            members.findById(removed.familyId(), saved.creatorId())
                    .filter(Member::isReachable)
                    .ifPresent(
                            author ->
                                    notifier.taskCancelled(author, saved, REMOVED_MEMBER_REASON));
        }
    }

    private Member member(long familyId, long memberId) {
        return members.findById(familyId, memberId)
                .orElseThrow(
                        () -> new DomainException.NotFound("member " + memberId + " not found"));
    }
}
