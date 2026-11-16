package com.salian.shortener.analytics;

import java.util.Locale;
import java.util.regex.Pattern;

/** Coarse, dependency-free user-agent bucketing. Deliberately simple; not a full UA parser. */
public final class UserAgentClassifier {

    public static final String BOT = "BOT";
    public static final String MOBILE = "MOBILE";
    public static final String DESKTOP = "DESKTOP";
    public static final String OTHER = "OTHER";
    public static final String UNKNOWN = "UNKNOWN";

    private static final Pattern BOT_PATTERN = Pattern.compile("bot|crawl|spider|slurp|curl|wget|python-requests|httpclient");
    private static final Pattern MOBILE_PATTERN = Pattern.compile("mobile|android|iphone|ipad|ipod");

    private UserAgentClassifier() {
    }

    public static String classify(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return UNKNOWN;
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        if (BOT_PATTERN.matcher(ua).find()) {
            return BOT;
        }
        if (MOBILE_PATTERN.matcher(ua).find()) {
            return MOBILE;
        }
        if (ua.startsWith("mozilla/")) {
            return DESKTOP;
        }
        return OTHER;
    }
}
