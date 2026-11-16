package com.salian.shortener.link;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BasicTargetUrlPolicyTest {

    private final BasicTargetUrlPolicy policy = new BasicTargetUrlPolicy();

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com", "http://example.com/a?b=c#d", "HTTPS://Example.com/path"})
    void acceptsAbsoluteHttpUrls(String url) {
        assertThatCode(() -> policy.check(URI.create(url))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "ftp://example.com/file", "/relative/path", "mailto:a@b.com",
            "data:text/html,hi", "https:///no-host"})
    void rejectsNonHttpOrRelativeUrls(String url) {
        assertThatThrownBy(() -> policy.check(URI.create(url))).isInstanceOf(InvalidTargetUrlException.class);
    }
}
