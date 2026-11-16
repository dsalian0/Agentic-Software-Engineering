package com.salian.shortener.link;

import com.salian.shortener.config.ShortenerProperties;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Abuse-prevention rules for link targets (from the clarified "links must be secure" requirement):
 * no embedded credentials, no internal/loopback/private destinations, no redirect loops through this
 * service, and no configured blocked domains. Hostnames are NOT resolved via DNS here (no network
 * I/O on the write path); only IP literals are range-checked. DNS-rebinding defence is out of scope.
 */
@Component
@Order(10)
public class UrlSafetyPolicy implements TargetUrlPolicy {

    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern NUMERIC_HOST = Pattern.compile("[0-9.]+|0x[0-9a-f.x]+");

    private final String ownHost;
    private final UrlSafetyProperties properties;

    public UrlSafetyPolicy(ShortenerProperties shortenerProperties, UrlSafetyProperties properties) {
        this.ownHost = hostOf(shortenerProperties.baseUrl());
        this.properties = properties;
    }

    @Override
    public void check(URI target) {
        if (target.getRawUserInfo() != null) {
            throw new InvalidTargetUrlException("targetUrl must not contain credentials");
        }
        String host = target.getHost() == null ? "" : target.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                || host.endsWith(".internal")) {
            throw new InvalidTargetUrlException("targetUrl must not point to an internal host");
        }
        if (host.startsWith("[") || IPV4_LITERAL.matcher(host).matches()) {
            checkIpLiteral(host);
        } else if (NUMERIC_HOST.matcher(host).matches()) {
            throw new InvalidTargetUrlException("targetUrl host must not be an encoded IP address");
        }
        if (ownHost != null && (host.equals(ownHost) || host.endsWith("." + ownHost))) {
            throw new InvalidTargetUrlException("targetUrl must not point back to this shortener");
        }
        for (String blocked : properties.blockedDomains()) {
            String domain = blocked.toLowerCase(Locale.ROOT);
            if (host.equals(domain) || host.endsWith("." + domain)) {
                throw new InvalidTargetUrlException("targetUrl domain is blocked");
            }
        }
    }

    private static void checkIpLiteral(String host) {
        String literal = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        InetAddress address;
        try {
            // Literal addresses are parsed locally; no DNS lookup happens for them.
            address = InetAddress.getByName(literal);
        } catch (UnknownHostException ex) {
            throw new InvalidTargetUrlException("targetUrl host is not a valid IP address");
        }
        byte[] bytes = address.getAddress();
        boolean uniqueLocalV6 = bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress() || uniqueLocalV6) {
            throw new InvalidTargetUrlException("targetUrl must not point to a private or reserved address");
        }
    }

    private static String hostOf(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
