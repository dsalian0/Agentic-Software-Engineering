package com.salian.shortener.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class UserAgentClassifierTest {

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0 Safari/537.36, DESKTOP",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Mobile/15E148, MOBILE",
            "Mozilla/5.0 (Linux; Android 14) Chrome/130.0 Mobile Safari/537.36, MOBILE",
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html), BOT",
            "curl/8.4.0, BOT",
            "SomeEmbeddedClient/1.0, OTHER",
            "NULL, UNKNOWN",
            "'   ', UNKNOWN"
    })
    void classifiesCommonAgents(String userAgent, String expected) {
        assertThat(UserAgentClassifier.classify(userAgent)).isEqualTo(expected);
    }
}
