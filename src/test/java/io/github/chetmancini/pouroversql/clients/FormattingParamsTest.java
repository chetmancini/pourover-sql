package io.github.chetmancini.pouroversql.clients;

import io.github.chetmancini.pouroversql.writers.KeyCaseFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class FormattingParamsTest {
    @Test
    public void testConstructor() {
        KeyCaseFormat keyCaseFormat = KeyCaseFormat.SNAKE_CASE;
        FormattingParams formattingParams = new FormattingParams(keyCaseFormat);
        assertEquals(keyCaseFormat, formattingParams.getKeyCaseFormat());
    }

    @Test
    public void testDefaultParams() {
        KeyCaseFormat defaultKeyCase = KeyCaseFormat.DEFAULT;
        assertEquals(defaultKeyCase, FormattingParams.getDefaultFormattingParams().getKeyCaseFormat());
    }
}
