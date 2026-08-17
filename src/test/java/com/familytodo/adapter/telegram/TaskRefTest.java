package com.familytodo.adapter.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.familytodo.adapter.telegram.view.TaskListView;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Ссылка на дело вместе с тем, откуда его открыли: по ней строится «← Назад». */
class TaskRefTest {

    @Nested
    class Lists {

        @Test
        void everyListKindSurvivesTheRoundTrip() {
            for (TaskListView.Kind kind : TaskListView.Kind.values()) {
                TaskRef parsed = TaskRef.parse(TaskRef.format(kind, 1234L));

                assertThat(parsed.kind()).isEqualTo(kind);
                assertThat(parsed.taskId()).isEqualTo(1234L);
                assertThat(parsed.isFromAgenda()).isFalse();
            }
        }

        @Test
        void garbageIsRefused() {
            assertThatThrownBy(() -> TaskRef.parse("z1"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> TaskRef.parse("aabc"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * У расписания своя ссылка: кроме дела она несёт горизонт.
     *
     * <p>⚠️ Горизонт обязателен. Без него «← Назад» из дела, открытого на неделе, возвращал бы на
     * день — то есть не туда, откуда человек пришёл.
     */
    @Nested
    class Agenda {

        @Test
        void carriesTheHorizonAndTheTask() {
            TaskRef parsed = TaskRef.parse(TaskRef.forAgenda(7, 1234L));

            assertThat(parsed.isFromAgenda()).isTrue();
            assertThat(parsed.agendaDays()).isEqualTo(7);
            assertThat(parsed.taskId()).isEqualTo(1234L);
        }

        /** Для вёрстки карточки расписание — то же, что «все дела»: автор → исполнители. */
        @Test
        void looksLikeTheAllListWhenTheCardIsDrawn() {
            assertThat(TaskRef.parse(TaskRef.forAgenda(30, 5L)).kind())
                    .isEqualTo(TaskListView.Kind.ALL);
        }

        @Test
        void everyHorizonFitsIntoTheCallbackLimit() {
            for (int days : com.familytodo.adapter.telegram.view.AgendaView.HORIZONS) {
                CallbackData data =
                        new CallbackData("t", "card", TaskRef.forAgenda(days, Long.MAX_VALUE));

                assertThat(data.serialize().length()).isLessThanOrEqualTo(CallbackData.MAX_BYTES);
            }
        }

        @Test
        void garbageIsRefused() {
            assertThatThrownBy(() -> TaskRef.parse("g7"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> TaskRef.parse("g7-"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> TaskRef.parse("g-1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
