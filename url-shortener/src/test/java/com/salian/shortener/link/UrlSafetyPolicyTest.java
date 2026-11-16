package com.salian.shortener.link;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.salian.shortener.config.ShortenerProperties;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlSafetyPolicyTest {

    private final UrlSafetyPolicy policy = new UrlSafetyPolicy(
            new ShortenerProperties("https://sho.rt", 7, 5),
            new UrlSafetyProperties(List.of("malware.example")));

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com/a", "https://8.8.8.8/dns", "https://[2001:4860:4860::8888]/",
            "https://sub.example.org:8443/x?y=z"})
    void allowsPublicDestinations(String url) {
        assertThatCode(() -> policy.check(URI.create(url))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost/admin", "http://app.localhost/", "http://printer.local/", "http://db.internal/",
            "http://127.0.0.1/", "http://10.0.0.5/", "http://192.168.1.1/", "http://172.16.0.1/",
            "http://169.254.169.254/latest/meta-data", "http://0.0.0.0/", "http://[::1]/", "http://[fd00::1]/",
            "http://2130706433/", "http://0x7f000001/",
            "https://user:secret@example.com/", "https://sho.rt/r/abc", "https://x.sho.rt/",
            "https://malware.example/", "https://cdn.malware.example/payload"})
    void rejectsUnsafeDestinations(String url) {
        assertThatThrownBy(() -> policy.check(URI.create(url))).isInstanceOf(InvalidTargetUrlException.class);
    }
}
