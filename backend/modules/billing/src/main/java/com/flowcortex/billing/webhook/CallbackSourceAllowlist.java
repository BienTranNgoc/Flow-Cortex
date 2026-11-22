package com.flowcortex.billing.webhook;

import com.flowcortex.billing.region.IpAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The addresses a provider calls back from: IPv4 or IPv6 addresses and CIDR blocks. Callers are matched as
 * address literals; a name is never resolved.
 */
public final class CallbackSourceAllowlist {

    /** One block: the network address and how many leading bits must match. */
    public record Entry(IpAddress network, int prefix) {

        boolean contains(IpAddress address) {
            if (address.ipv4() != network.ipv4()) {
                return false;
            }
            if (network.ipv4()) {
                int shift = 32 - prefix;
                return (address.v4() >> shift) == (network.v4() >> shift);
            }
            int shift = 128 - prefix;
            return address.v6().shiftRight(shift).equals(network.v6().shiftRight(shift));
        }
    }

    /** The readable entries, and the text of every piece that could not be read. */
    public record Result(List<Entry> entries, List<String> unreadable) {
    }

    private final List<Entry> entries;

    public CallbackSourceAllowlist(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** Splits a comma-separated list; a blank piece is skipped, an unreadable one is reported. */
    public static Result parse(String text) {
        List<Entry> entries = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        if (text != null) {
            for (String piece : text.split(",", -1)) {
                String trimmed = piece.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                Optional<Entry> entry = entry(trimmed);
                if (entry.isPresent()) {
                    entries.add(entry.get());
                } else {
                    unreadable.add(trimmed);
                }
            }
        }
        return new Result(List.copyOf(entries), List.copyOf(unreadable));
    }

    public List<Entry> entries() {
        return entries;
    }

    /** Whether the caller's address is inside any entry; null, blank and non-address text are refused. */
    public boolean admits(String remoteAddress) {
        Optional<IpAddress> address = IpAddress.parse(remoteAddress);
        return address.isPresent() && entries.stream().anyMatch(entry -> entry.contains(address.get()));
    }

    private static Optional<Entry> entry(String text) {
        int slash = text.indexOf('/');
        String addressText = slash < 0 ? text : text.substring(0, slash);
        Optional<IpAddress> address = IpAddress.parse(addressText);
        if (address.isEmpty()) {
            return Optional.empty();
        }
        IpAddress network = address.get();
        int maxPrefix = network.ipv4() ? 32 : 128;
        if (slash < 0) {
            return Optional.of(new Entry(network, maxPrefix));
        }
        // A mapped IPv6 literal collapses to IPv4, so a prefix written against 128 bits would be misread.
        if (network.ipv4() && addressText.indexOf(':') >= 0) {
            return Optional.empty();
        }
        String prefixText = text.substring(slash + 1);
        if (prefixText.isEmpty() || prefixText.length() > 3 || !prefixText.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return Optional.empty();
        }
        int prefix = Integer.parseInt(prefixText);
        return prefix <= maxPrefix ? Optional.of(new Entry(network, prefix)) : Optional.empty();
    }
}
