package com.flowcortex.workflow.application;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * Masks a client address before it is stored: IPv4 keeps two octets, IPv6 loses its last two groups.
 * An address that is missing or does not parse is stored as null rather than kept whole.
 */
public final class NetworkMask {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private NetworkMask() {
    }

    public static String of(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return null;
        }
        String address = remoteAddr.trim();
        if (address.indexOf(':') < 0) {
            return maskIpv4(address);
        }
        try {
            // A colon means an IPv6 literal, so this never resolves a host name.
            InetAddress parsed = InetAddress.getByName(address);
            byte[] bytes = parsed.getAddress();
            if (parsed instanceof Inet4Address) {
                return (bytes[0] & 0xff) + "." + (bytes[1] & 0xff) + ".x.x";
            }
            StringBuilder masked = new StringBuilder();
            for (int group = 0; group < 6; group++) {
                masked.append(Integer.toHexString(((bytes[group * 2] & 0xff) << 8) | (bytes[group * 2 + 1] & 0xff)))
                        .append(':');
            }
            return masked.append("x:x").toString();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static String maskIpv4(String address) {
        if (!IPV4.matcher(address).matches()) {
            return null;
        }
        String[] octets = address.split("\\.");
        for (String octet : octets) {
            if (Integer.parseInt(octet) > 255) {
                return null;
            }
        }
        return Integer.parseInt(octets[0]) + "." + Integer.parseInt(octets[1]) + ".x.x";
    }
}
