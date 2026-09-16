package cn.timebank.dataexchange.application.validation;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan §3.1 / gap G-1. The case that matters most is the round trip: the
 * exporter quotes a field containing a comma, and the old
 * {@code split(",", -1)} could not read it back, silently shifting every
 * subsequent column.
 */
class Rfc4180ReaderTest {

    @Test
    void readsPlainFields() {
        assertThat(Rfc4180Reader.parseLine("a,b,c")).containsExactly("a", "b", "c");
    }

    @Test
    void preservesEmptyTrailingFields() {
        // split(",", -1) got this right and the replacement must not regress it.
        assertThat(Rfc4180Reader.parseLine("a,,")).containsExactly("a", "", "");
    }

    @Test
    void readsBackAQuotedFieldContainingAComma() {
        // The exact round trip that used to corrupt a roster.
        assertThat(Rfc4180Reader.parseLine("a45b9def,\"Lee, Alex\",grade_6"))
                .containsExactly("a45b9def", "Lee, Alex", "grade_6");
    }

    @Test
    void readsDoubledQuotesAsOneLiteralQuote() {
        assertThat(Rfc4180Reader.parseLine("\"say \"\"hi\"\"\",x")).containsExactly("say \"hi\"", "x");
    }

    @Test
    void readsANewlineEmbeddedInAQuotedField() throws Exception {
        BufferedReader reader = new BufferedReader(new StringReader("\"line one\nline two\",after\n"));
        List<String> fields = Rfc4180Reader.readRecord(reader);
        assertThat(fields).containsExactly("line one\nline two", "after");
    }

    @Test
    void returnsNullAtEndOfInput() throws Exception {
        assertThat(Rfc4180Reader.readRecord(new BufferedReader(new StringReader("")))).isNull();
    }

    @Test
    void rejectsAnUnterminatedQuotedField() {
        assertThatThrownBy(() -> Rfc4180Reader.parseLine("\"never closed"))
                .isInstanceOf(Rfc4180Reader.MalformedCsvException.class);
    }

    @Test
    void rejectsAQuoteAppearingMidField() {
        assertThatThrownBy(() -> Rfc4180Reader.parseLine("ab\"cd"))
                .isInstanceOf(Rfc4180Reader.MalformedCsvException.class);
    }
}
