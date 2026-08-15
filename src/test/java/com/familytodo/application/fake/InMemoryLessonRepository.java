package com.familytodo.application.fake;

import com.familytodo.application.port.LessonRepository;
import com.familytodo.domain.Lesson;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Фейк хранилища расписания. Фильтр по семье безусловный — как и в SQL. */
public final class InMemoryLessonRepository implements LessonRepository {

    private final Map<Long, Lesson> lessons = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public long nextId() {
        return sequence.incrementAndGet();
    }

    @Override
    public int replace(long familyId, long memberId, List<Lesson> replacement) {
        List<Long> doomed =
                lessons.values().stream()
                        .filter(lesson -> lesson.familyId() == familyId && lesson.memberId() == memberId)
                        .map(Lesson::id)
                        .toList();
        doomed.forEach(lessons::remove);
        replacement.forEach(lesson -> lessons.put(lesson.id(), lesson));
        return doomed.size();
    }

    @Override
    public List<Lesson> findByMember(long familyId, long memberId) {
        return sorted(
                lessons.values().stream()
                        .filter(lesson -> lesson.familyId() == familyId && lesson.memberId() == memberId)
                        .toList());
    }

    @Override
    public List<Lesson> findVisible(long familyId, Long visibleToMemberId) {
        return sorted(
                lessons.values().stream()
                        .filter(lesson -> lesson.familyId() == familyId)
                        .filter(
                                lesson ->
                                        visibleToMemberId == null
                                                || lesson.memberId() == visibleToMemberId)
                        .toList());
    }

    /** Тот же порядок, что в запросе: по дню недели, затем по времени начала. */
    private static List<Lesson> sorted(List<Lesson> found) {
        List<Lesson> copy = new ArrayList<>(found);
        copy.sort(Comparator.comparing(Lesson::day).thenComparing(Lesson::startsAt));
        return List.copyOf(copy);
    }
}
