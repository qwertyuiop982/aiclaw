package com.operit.aiclaw.tools;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/** Rejects non-web and private/local destinations to reduce SSRF risk. */
final class UrlPolicy {
    private UrlPolicy() {}

    static URI validate(String value) {
        if (value == null || value.isBlank()) throw new ToolException("URL must not be blank");
        final URI uri;
        try { uri = URI.create(value); } catch (IllegalArgumentException e) {
            throw new ToolException("invalid URL");
        }
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || uri.getHost() == null) {
            throw new ToolException("only http/https URLs with a host are allowed");
        }
        String host = uri.getHost();
        String lower = host.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.equals("metadata.google.internal")) {
            throw new ToolException("local destinations are not allowed");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
                    throw new ToolException("private or local destinations are not allowed");
                }
            }
        } catch (UnknownHostException e) {
            throw new ToolException("cannot resolve URL host: " + host, e);
        }
        return uri;
    }
}