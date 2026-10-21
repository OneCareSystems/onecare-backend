package com.onecare.backend.chatbot;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

public final class ClientIpResolver {
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9a-fA-F:.]+");

    private record Cidr(byte[] network, int prefixBits) {
        boolean matches(byte[] ip) {
            if (ip.length != network.length) return false;
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) if (ip[i] != network[i]) return false;
            int rest = prefixBits % 8;
            if (rest == 0) return true;
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (ip[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }

    private final List<Cidr> trusted = new ArrayList<>();

    /** Entries: "10.0.0.5", "10.0.0.0/8", "::1", "fd00::/8". A bad entry fails at startup. */
    public ClientIpResolver(Collection<String> trustedProxies) {
        if (trustedProxies == null) return;
        for (String raw : trustedProxies) {
            if (raw == null || raw.isBlank()) continue;
            String entry = raw.trim();
            String addr = entry;
            Integer prefix = null;
            int slash = entry.indexOf('/');
            if (slash >= 0) {
                addr = entry.substring(0, slash);
                try {
                    prefix = Integer.parseInt(entry.substring(slash + 1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid trusted proxy: " + raw);
                }
            }
            byte[] bytes = parse(addr);
            if (bytes == null) throw new IllegalArgumentException("Invalid trusted proxy: " + raw);
            int bits = prefix == null ? bytes.length * 8 : prefix;
            if (bits < 0 || bits > bytes.length * 8) throw new IllegalArgumentException("Invalid trusted proxy: " + raw);
            trusted.add(new Cidr(bytes, bits));
        }
    }

    /** The client address for this request. Never null. */
    public String resolve(String remoteAddr, String forwardedFor) {
        String remote = clean(remoteAddr);
        if (remote == null) return "unknown";
        byte[] remoteBytes = parse(remote);
        if (remoteBytes == null || !isTrusted(remoteBytes)) return remote;          // direct client: ignore the header
        if (forwardedFor == null || forwardedFor.isBlank()) return remote;
        String[] parts = forwardedFor.split(",");
        for (int i = parts.length - 1; i >= 0; i--) {
            String candidate = clean(parts[i]);
            byte[] bytes = candidate == null ? null : parse(candidate);
            if (bytes == null) return remote;                                        // junk in the chain: trust none of it
            if (!isTrusted(bytes)) return candidate;
        }
        return remote;
    }

    /** What the limiter counts: IPv4 as is, IPv6 as its /64 network. */
    public static String rateLimitKey(String ip) {
        String cleaned = clean(ip);
        byte[] bytes = cleaned == null ? null : parse(cleaned);
        if (bytes == null) return cleaned == null ? "unknown" : cleaned;
        StringBuilder key = new StringBuilder(bytes.length == 16 ? "v6:" : "v4:");
        int count = bytes.length == 16 ? 8 : 4;
        for (int i = 0; i < count; i++) key.append(String.format("%02x", bytes[i] & 0xFF));
        return key.toString();
    }

    private boolean isTrusted(byte[] ip) {
        for (Cidr cidr : trusted) if (cidr.matches(ip)) return true;
        return false;
    }

    private static String clean(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty()) return null;
        if (v.startsWith("[")) {                                                     // [::1] or [::1]:443
            int end = v.indexOf(']');
            if (end < 0) return null;
            v = v.substring(1, end);
        } else if (v.matches("\\d{1,3}(\\.\\d{1,3}){3}:\\d{1,5}")) {                // 1.2.3.4:5678
            v = v.substring(0, v.lastIndexOf(':'));
        }
        int zone = v.indexOf('%');                                                   // fe80::1%eth0
        if (zone >= 0) v = v.substring(0, zone);
        return v.isEmpty() ? null : v;
    }

    /** Literal addresses only: never triggers a DNS lookup. IPv4-mapped IPv6 becomes plain IPv4. */
    private static byte[] parse(String text) {
        if (text == null) return null;
        var m4 = IPV4.matcher(text);
        if (m4.matches()) {
            byte[] out = new byte[4];
            for (int i = 0; i < 4; i++) {
                int octet = Integer.parseInt(m4.group(i + 1));
                if (octet > 255) return null;
                out[i] = (byte) octet;
            }
            return out;
        }
        if (text.indexOf(':') >= 0 && IPV6_CHARS.matcher(text).matches()) {
            try {
                return InetAddress.getByName(text).getAddress();
            } catch (UnknownHostException e) {
                return null;
            }
        }
        return null;
    }
}
