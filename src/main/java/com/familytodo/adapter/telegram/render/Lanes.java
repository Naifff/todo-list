package com.familytodo.adapter.telegram.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Раскладка пересекающихся дел по колонкам внутри одного дня.
 *
 * <p>Два дела на одно время не рисуются друг поверх друга: они делят ширину дня, как в макете.
 * Ширина считается по <b>группе связанных</b> дел, а не по дню целиком — иначе одно утреннее
 * пересечение ужимало бы вдвое и одинокое вечернее дело.
 *
 * <p>Логика вынесена из рисующего кода намеренно: её можно проверить точно, а картинку — только на
 * «непустая и не упала».
 */
public final class Lanes {

    private Lanes() {}

    /** Отрезок дня в секундах от полуночи. Конец не включается: 10:00 не пересекается с 10:00. */
    public record Span<T>(T value, int fromSecond, int toSecond) {}

    /**
     * @param lane номер колонки, с нуля
     * @param lanes сколько всего колонок в группе — знаменатель ширины
     */
    public record Placed<T>(T value, int fromSecond, int toSecond, int lane, int lanes) {}

    public static <T> List<Placed<T>> pack(List<Span<T>> spans) {
        List<Span<T>> ordered = new ArrayList<>(spans);
        ordered.sort(Comparator.comparingInt(Span<T>::fromSecond).thenComparingInt(Span::toSecond));

        List<Placed<T>> result = new ArrayList<>(ordered.size());
        List<Integer> laneEnds = new ArrayList<>();

        // границы текущей группы: индекс первого элемента и момент, когда группа закроется
        int clusterStart = 0;
        int clusterEnd = Integer.MIN_VALUE;

        for (Span<T> span : ordered) {
            if (span.fromSecond() >= clusterEnd) {
                // ни с чем из предыдущих не пересекается — предыдущая группа закончилась
                assignWidth(result, clusterStart, laneEnds.size());
                clusterStart = result.size();
                laneEnds.clear();
            }

            int lane = firstFreeLane(laneEnds, span.fromSecond());
            if (lane == laneEnds.size()) {
                laneEnds.add(span.toSecond());
            } else {
                laneEnds.set(lane, span.toSecond());
            }

            result.add(new Placed<>(span.value(), span.fromSecond(), span.toSecond(), lane, 0));
            clusterEnd = Math.max(clusterEnd, span.toSecond());
        }
        assignWidth(result, clusterStart, laneEnds.size());
        return result;
    }

    private static int firstFreeLane(List<Integer> laneEnds, int from) {
        for (int lane = 0; lane < laneEnds.size(); lane++) {
            if (laneEnds.get(lane) <= from) {
                return lane;
            }
        }
        return laneEnds.size();
    }

    /** Ширина известна только когда группа закрылась: до этого неясно, сколько в ней колонок. */
    private static <T> void assignWidth(List<Placed<T>> result, int from, int lanes) {
        for (int i = from; i < result.size(); i++) {
            Placed<T> p = result.get(i);
            result.set(i, new Placed<>(p.value(), p.fromSecond(), p.toSecond(), p.lane(), lanes));
        }
    }
}
