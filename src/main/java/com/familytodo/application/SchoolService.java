package com.familytodo.application;

import com.familytodo.application.port.FamilyRepository;
import com.familytodo.application.port.LessonRepository;
import com.familytodo.application.port.MemberRepository;
import com.familytodo.domain.DomainException;
import com.familytodo.domain.Lesson;
import com.familytodo.domain.Member;
import com.familytodo.domain.MemberStatus;
import com.familytodo.domain.Role;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Расписание уроков.
 *
 * <p>Ни одного уведомления: звонок и так звенит, а сообщение на каждый урок — тридцать штук в
 * неделю. По той же причине уроки не попадают в напоминания.
 */
@Service
public class SchoolService {

    private static final Logger log = LoggerFactory.getLogger(SchoolService.class);

    private final LessonRepository lessons;
    private final MemberRepository members;
    private final FamilyRepository families;
    private final LessonParser parser;
    private final Clock clock;

    public SchoolService(
            LessonRepository lessons,
            MemberRepository members,
            FamilyRepository families,
            LessonParser parser,
            Clock clock) {
        this.lessons = lessons;
        this.members = members;
        this.families = families;
        this.parser = parser;
        this.clock = clock;
    }

    /**
     * Заменить расписание участника целиком.
     *
     * <p>Сообщение принимается <b>целиком или отвергается целиком</b>: «добавлено 27» при тридцати
     * строках оставляет человека гадать, какие три пропали.
     *
     * @return сколько уроков было и сколько стало
     * @throws IllegalArgumentException если сообщение не разобрано — текст наружу не выносим, в нём
     *     пользовательский ввод
     */
    public Replaced replace(Member actor, long memberId, String written) {
        Member pupil = requireManageable(actor, memberId);
        LocalDate today = family(actor).today(clock.instant());

        LessonParser.Schedule schedule =
                parser.parse(written, today)
                        .orElseThrow(() -> new IllegalArgumentException("schedule is not parsed"));

        List<Lesson> parsed = new ArrayList<>();
        for (LessonParser.Parsed lesson : schedule.lessons()) {
            parsed.add(
                    Lesson.create(
                            lessons.nextId(),
                            pupil.familyId(),
                            pupil.id(),
                            lesson.day(),
                            lesson.startsAt(),
                            lesson.endsAt(),
                            lesson.subject(),
                            today,
                            schedule.validTo(),
                            clock.instant()));
        }

        int before = lessons.replace(pupil.familyId(), pupil.id(), parsed);
        log.info("schedule of member {} replaced: {} lessons were, {} now", pupil.id(), before, parsed.size());
        return new Replaced(before, parsed.size());
    }

    /** @param before сколько уроков было до замены, {@param after} — сколько стало */
    public record Replaced(int before, int after) {}

    public int clear(Member actor, long memberId) {
        Member pupil = requireManageable(actor, memberId);
        int removed = lessons.replace(pupil.familyId(), pupil.id(), List.of());
        log.info("schedule of member {} cleared, {} lessons removed", pupil.id(), removed);
        return removed;
    }

    /** Расписание одного участника — с проверкой видимости. */
    public List<Lesson> of(Member viewer, long memberId) {
        requireVisible(viewer, memberId);
        return lessons.findByMember(viewer.familyId(), memberId);
    }

    /**
     * Уроки для календаря и дайджеста — <b>только свои</b>, любому.
     *
     * <p>⚠️ Это сознательное исключение из правила «родитель видит календарь семьи». Прежде здесь
     * стояла видимость задач, и 16 августа с телефона стало видно, чем она обходится: у ребёнка
     * тридцать уроков в неделю, и в месячной сетке родителя учебный день забит ими целиком —
     * единственное настоящее дело в тот день теряется между «Химия» и «География».
     *
     * <p>Разница в вопросах. Правило видимости задач отвечает «что мне можно увидеть», а календарь
     * отвечает «кто когда занят»: урок ребёнка родителя не занимает, и в его сетке это шум. Тот же
     * довод уже разводил списки и дайджест.
     *
     * <p>Расписание ребёнка родителю по-прежнему доступно — {@link #of(Member, long)}, то есть
     * {@code /school} → выбрать ребёнка. Экран, на котором его и спрашивают.
     */
    public List<Lesson> own(Member viewer) {
        return lessons.findByMember(viewer.familyId(), viewer.id());
    }

    /**
     * Расписание правит родитель — любому в семье, — и ребёнок себе.
     *
     * <p>Расписание не поручение, а факт о школьном дне: запрещать ребёнку вносить свои уроки не за
     * что. А вот брату — уже за что: чужое расписание правит взрослый.
     */
    private Member requireManageable(Member actor, long memberId) {
        Member pupil = requireMember(actor.familyId(), memberId);
        if (actor.role() != Role.PARENT && actor.id() != pupil.id()) {
            throw new DomainException.NotPermitted("actor may not manage this schedule");
        }
        return pupil;
    }

    /** Видимость чужого расписания — как у задач: родитель видит семью, ребёнок только себя. */
    private void requireVisible(Member viewer, long memberId) {
        requireMember(viewer.familyId(), memberId);
        if (viewer.role() == Role.CHILD && viewer.id() != memberId) {
            throw new DomainException.NotFound("schedule not found");
        }
    }

    private Member requireMember(long familyId, long memberId) {
        Optional<Member> found = members.findById(familyId, memberId);
        Member member =
                found.orElseThrow(() -> new DomainException.NotFound("member not found"));
        if (member.status() != MemberStatus.ACTIVE) {
            throw new DomainException.NotFound("member not found");
        }
        return member;
    }

    private com.familytodo.domain.Family family(Member member) {
        return families.findById(member.familyId())
                .orElseThrow(() -> new DomainException.NotFound("family not found"));
    }
}
