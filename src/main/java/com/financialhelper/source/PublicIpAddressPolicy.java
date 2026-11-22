package com.financialhelper.source;

import java.net.InetAddress;

/** Conservative global-unicast policy. Special-purpose and transition ranges fail closed. */
final class PublicIpAddressPolicy {
    private PublicIpAddressPolicy() {}

    static boolean isPublic(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255;
            return !(a == 0 || a == 10 || a == 127 || a >= 224
                    || a == 100 && b >= 64 && b <= 127
                    || a == 169 && b == 254
                    || a == 172 && b >= 16 && b <= 31
                    || a == 192 && (b == 0 && (c == 0 || c == 2)
                            || b == 31 && c == 196 || b == 52 && c == 193
                            || b == 88 && c == 99 || b == 168 || b == 175 && c == 48)
                    || a == 198 && (b == 18 || b == 19 || b == 51 && c == 100)
                    || a == 203 && b == 0 && c == 113);
        }
        if (bytes.length != 16 || (bytes[0] & 0xe0) != 0x20) return false;
        int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255, d = bytes[3] & 255;
        return !(a == 0x20 && b == 0x01 && ((c & 0xfe) == 0 || c == 0x0d && d == 0xb8)
                || a == 0x20 && b == 0x02
                || a == 0x26 && b == 0x20 && c == 0 && d == 0x4f
                        && (bytes[4] & 255) == 0x80 && bytes[5] == 0
                || a == 0x3f && b == 0xfe
                || a == 0x3f && b == 0xff && (c & 0xf0) == 0);
    }
}
