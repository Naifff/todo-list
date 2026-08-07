package com.familytodo.adapter.telegram.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Раскладка пересекающихся дел по колонкам внутри дня.
 *
 * <p>Два дела на одно время не должны рисоваться друг поверх друга: в макете они делят ширину дня.
 * Это единственная содержательная логика во всей отрисовке, поэтому она вынесена из рисующего кода
 * и проверяется точно, а не «картинка получилась непустая».
 */
class LanesTest {

    @Nested
    class Packing {

        @Test
        void singleTaskTakesTheWholeWidth() {
            List<Lanes.Placed<String>> placed = Lanes.pack(List.of(span("A", 9, 10)));

            assertThat(placed).singleElement().satisfies(p -> {
                assertThat(p.lane()).isZero();
                assertThat(p.lanes()).isEqualTo(1);
            });
        }

        @Test
        void tasksThatDoNotOverlapShareTheSameLane() {
            List<Lanes.Placed<String>> placed =
                    Lanes.pack(List.of(span("A", 9, 10), span("B", 10, 11)));

            assertThat(placed).allSatisfy(p -> assertThat(p.lane()).isZero());
            assertThat(placed).allSatisfy(p -> assertThat(p.lanes()).isEqualTo(1));
        }

        /** Смежные концы — не пересечение: дело, кончающееся в 10:00, не мешает начатому в 10:00. */
        @Test
        void touchingEndsDoNotCountAsOverlap() {
            List<Lanes.Placed<String>> placed =
                    Lanes.pack(List.of(span("A", 9, 10), span("B", 10, 11)));

            assertThat(placed).allSatisfy(p -> assertThat(p.lanes()).isEqualTo(1));
        }

        @Test
        void twoOverlappingTasksSplitTheWidth() {
            List<Lanes.Placed<String>> placed =
                    Lanes.pack(List.of(span("A", 9, 11), span("B", 10, 12)));

            assertThat(lanesOf(placed)).containsExactly(0, 1);
            assertThat(placed).allSatisfy(p -> assertThat(p.lanes()).isEqualTo(2));
        }

        @Test
        void threeWayOverlapSplitsIntoThree() {
            List<Lanes.Placed<String>> placed =
                    Lanes.pack(List.of(span("A", 9, 12), span("B", 9, 12), span("C", 9, 12)));

            assertThat(lanesOf(placed)).containsExactly(0, 1, 2);
            assertThat(placed).allSatisfy(p -> assertThat(p.lanes()).isEqualTo(3));
        }

        /** Освободившаяся колонка переиспользуется, иначе день сужается до нитки без причины. */
        @Test
        void freedLaneIsReused() {
            List<Lanes.Placed<String>> placed =
                    Lanes.pack(List.of(span("A", 9, 11), span("B", 10, 12), span("C", 11, 13)));

            assertThat(lanesOf(placed)).containsExactly(0, 1, 0);
        }

        /**
         * Ширина считается по группе связанных дел, а не по всему дню: утреннее пересечение не
         * должно ужимать одинокое вечернее дело вдвое.
         */
        @Test
        void widthIsPerClusterNotPerDay() {
            List<Lanes.Placed<String>> placed =
                    Lanes.pack(List.of(span("A", 9, 11), span("B", 10, 12), span("C", 18, 19)));

            assertThat(placed.get(0).lanes()).isEqualTo(2);
            assertThat(placed.get(1).lanes()).isEqualTo(2);
            assertThat(placed.get(2).lanes())
                    .describedAs("вечернее дело ни с чем не пересекается")
                    .isEqualTo(1);
        }

        @Test
        void unsortedInputIsHandled() {
            List<Lanes.Placed<String>> placed =
                    Lanes.pack(List.of(span("late", 18, 19), span("early", 9, 10)));

            assertThat(placed.stream().map(Lanes.Placed::value)).containsExactly("early", "late");
        }

        @Test
        void emptyDayGivesNothing() {
            assertThat(Lanes.pack(List.<Lanes.Span<String>>of())).isEmpty();
        }
    }

    private static Lanes.Span<String> span(String name, int fromHour, int toHour) {
        return new Lanes.Span<>(
                name, LocalTime.of(fromHour, 0).toSecondOfDay(), LocalTime.of(toHour, 0).toSecondOfDay());
    }

    private static List<Integer> lanesOf(List<Lanes.Placed<String>> placed) {
        return placed.stream().map(Lanes.Placed::lane).toList();
    }
}
