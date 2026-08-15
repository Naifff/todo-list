package com.familytodo.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Урок в расписании. Ни Spring, ни JPA, ни одной аннотации — как и у {@link Task}.
 *
 * <p>Это <b>не задача</b>, и разница не в размере. У урока нет исполнителя — никто не *просил*
 * ребёнка сделать алгебру; от него нельзя отказаться с причиной, и его нельзя «сделать»: он просто
 * идёт. Шесть уроков × пять дней × тридцать пять недель — тысяча строк в год на одного ребёнка, и
 * строками {@code task} они забили бы {@code /my}, дайджест и календарь, то есть ровно те экраны,
 * ради которых бот и делался.
 *
 * <p>Из этого следует требование, ради которого всё и разделено: в списках дел урок появиться
 * <b>не может</b>. Они выбирают из {@code task}, а уроков там нет вовсе — не фильтр, который забудут
 * дописать в новом запросе, а структурная невозможность.
 *
 * <p>⚠️ Урок — <b>правило, и оно не материализуется</b>, в отличие от вхождений серии. Вхождения
 * становятся строками потому, что у каждого своя судьба: одно сделано, другое отклонено с причиной.
 * У урока судьбы нет — он либо в расписании, либо нет, — поэтому конкретный день получается
 * наложением правила на дату при отрисовке окна.
 */
public final class Lesson {

    /** «Изобразительное искусство» помещается, сочинение — уже нет. */
    public static final int MAX_SUBJECT_LENGTH = 60;

    private final long id;
    private final long familyId;
    private final long memberId;
    private final DayOfWeek day;
    private final LocalTime startsAt;
    private final LocalTime endsAt;
    private final String subject;
    private final LocalDate validFrom;
    private final LocalDate validTo;
    private final Instant createdAt;

    private Lesson(
            long id,
            long familyId,
            long memberId,
            DayOfWeek day,
            LocalTime startsAt,
            LocalTime endsAt,
            String subject,
            LocalDate validFrom,
            LocalDate validTo,
            Instant createdAt) {
        this.id = id;
        this.familyId = familyId;
        this.memberId = memberId;
        this.day = requireDay(day);
        this.startsAt = requireTime(startsAt, "lesson start");
        this.endsAt = requireEnd(startsAt, endsAt);
        this.subject = requireSubject(subject);
        this.validFrom = requireDate(validFrom);
        this.validTo = requireValidTo(validFrom, validTo);
        this.createdAt = createdAt;
    }

    public static Lesson create(
            long id,
            long familyId,
            long memberId,
            DayOfWeek day,
            LocalTime startsAt,
            LocalTime endsAt,
            String subject,
            LocalDate validFrom,
            LocalDate validTo,
            Instant createdAt) {
        return new Lesson(
                id, familyId, memberId, day, startsAt, endsAt, subject, validFrom, validTo,
                createdAt);
    }

    /** Восстановление из хранилища: состояние приходит как есть. */
    public static Lesson restore(
            long id,
            long familyId,
            long memberId,
            DayOfWeek day,
            LocalTime startsAt,
            LocalTime endsAt,
            String subject,
            LocalDate validFrom,
            LocalDate validTo,
            Instant createdAt) {
        return new Lesson(
                id, familyId, memberId, day, startsAt, endsAt, subject, validFrom, validTo,
                createdAt);
    }

    /** Идёт ли урок в этот день. Срок действия — часть правила, а не отдельная проверка снаружи. */
    public boolean occursOn(LocalDate date) {
        if (date.getDayOfWeek() != day) {
            return false;
        }
        if (date.isBefore(validFrom)) {
            return false;
        }
        return validTo == null || !date.isAfter(validTo);
    }

    /**
     * Момент начала в зоне семьи: 08:30 — это её половина девятого, а не серверная.
     *
     * <p>В отличие от вхождения серии, момент здесь <b>не хранится</b>, а считается каждый раз:
     * хранить нечего, строки на конкретный день не существует.
     */
    public Instant startOf(LocalDate date, ZoneId zone) {
        return at(date, startsAt, zone);
    }

    public Instant endOf(LocalDate date, ZoneId zone) {
        return at(date, endsAt, zone);
    }

    private static Instant at(LocalDate date, LocalTime time, ZoneId zone) {
        return ZonedDateTime.of(date, time, zone).toInstant();
    }

    public long id() {
        return id;
    }

    public long familyId() {
        return familyId;
    }

    public long memberId() {
        return memberId;
    }

    public DayOfWeek day() {
        return day;
    }

    public LocalTime startsAt() {
        return startsAt;
    }

    public LocalTime endsAt() {
        return endsAt;
    }

    public String subject() {
        return subject;
    }

    public LocalDate validFrom() {
        return validFrom;
    }

    public LocalDate validTo() {
        return validTo;
    }

    public Instant createdAt() {
        return createdAt;
    }

    private static DayOfWeek requireDay(DayOfWeek day) {
        if (day == null) {
            throw new IllegalArgumentException("lesson day is required");
        }
        return day;
    }

    private static LocalTime requireTime(LocalTime time, String what) {
        if (time == null) {
            throw new IllegalArgumentException(what + " is required");
        }
        return time;
    }

    /**
     * Конец строго позже начала. Урок нулевой длины — не урок, а опечатка в звонках; ночного урока,
     * в отличие от семейного дела, не бывает, поэтому переход через полночь тут именно ошибка.
     */
    private static LocalTime requireEnd(LocalTime startsAt, LocalTime endsAt) {
        requireTime(endsAt, "lesson end");
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("lesson ends before it starts");
        }
        return endsAt;
    }

    private static String requireSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject is required");
        }
        String trimmed = subject.strip();
        if (trimmed.length() > MAX_SUBJECT_LENGTH) {
            throw new IllegalArgumentException("subject is longer than " + MAX_SUBJECT_LENGTH);
        }
        return trimmed;
    }

    private static LocalDate requireDate(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("schedule start is required");
        }
        return date;
    }

    private static LocalDate requireValidTo(LocalDate validFrom, LocalDate validTo) {
        if (validTo != null && validTo.isBefore(validFrom)) {
            throw new IllegalArgumentException("schedule ends before it starts");
        }
        return validTo;
    }
}
