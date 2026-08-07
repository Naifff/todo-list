package com.familytodo.adapter.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CallbackDataTest {

    @Test
    void parsesTaskAction() {
        CallbackData data = CallbackData.parse("t:done:1234");

        assertThat(data.prefix()).isEqualTo("t");
        assertThat(data.action()).isEqualTo("done");
        assertThat(data.longArgument()).isEqualTo(1234L);
    }

    @Test
    void roundTripsThroughSerialization() {
        CallbackData data = CallbackData.of("t", "decline", 987654321L);

        assertThat(CallbackData.parse(data.serialize())).isEqualTo(data);
    }

    /** Строка приходит от клиента — на мусор нужен отказ, а не исключение где-то ниже по стеку. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "t",
                "t:done",
                "t:done:1234:extra",
                "t::1234",
                "::",
                "T:done:1234",
                "t:DONE:1234",
                "t:done:тысяча",
                "t:done:1234; drop table task",
                "верхний:уровень:1",
                "toolongprefix:done:1"
            })
    void rejectsMalformedInput(String raw) {
        assertThatThrownBy(() -> CallbackData.parse(raw))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> CallbackData.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonNumericArgumentOnlyWhenReadAsNumber() {
        CallbackData data = CallbackData.parse("t:page:abc");

        assertThatThrownBy(data::longArgument).isInstanceOf(IllegalArgumentException.class);
    }

    /** 64 байта — жёсткий предел Telegram: строка длиннее просто не дойдёт до клиента. */
    @Test
    void neverExceedsSixtyFourBytes() {
        CallbackData longest = CallbackData.of("decline", "decline", Long.MAX_VALUE);

        assertThat(longest.serialize().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(CallbackData.MAX_BYTES);
    }

    @Test
    void refusesToBuildOversizedData() {
        assertThatThrownBy(() -> new CallbackData("t", "done", "a".repeat(40) + "b"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
