package com.innogrid.aims.infra.ipam.service;

/**
 * IPv4 CIDR 계산 유틸.
 * IP 주소 범위, 전체 호스트 수, 특정 IP의 CIDR 소속 여부를 계산한다.
 */
public final class CidrUtil {

    private CidrUtil() {}

    public record Range(String first, String last, long totalHosts) {}

    /**
     * CIDR의 사용 가능 범위와 전체 호스트 수.
     * 관례상 네트워크/브로드캐스트를 제외한 호스트 수를 totalHosts로 본다(/31,/32는 예외 처리).
     */
    public static Range range(String cidr) {
        int[] parsed = parse(cidr);
        long base = parsed[0] & 0xFFFFFFFFL;
        int prefix = parsed[1];

        long size = 1L << (32 - prefix);
        long network = base & maskFor(prefix);
        long broadcast = network + size - 1;

        long firstHost;
        long lastHost;
        long totalHosts;
        if (prefix >= 31) {
            firstHost = network;
            lastHost = broadcast;
            totalHosts = size;
        } else {
            firstHost = network + 1;
            lastHost = broadcast - 1;
            totalHosts = size - 2;
        }
        return new Range(toIp(firstHost), toIp(lastHost), totalHosts);
    }

    /** ip가 cidr 범위(네트워크~브로드캐스트) 안에 있는지. */
    public static boolean contains(String cidr, String ip) {
        try {
            int[] parsed = parse(cidr);
            long base = parsed[0] & 0xFFFFFFFFL;
            int prefix = parsed[1];
            long mask = maskFor(prefix);
            long network = base & mask;
            long addr = ipToLong(ip);
            return (addr & mask) == network;
        } catch (Exception e) {
            return false;
        }
    }

    /** CIDR 형식 검증. */
    public static boolean isValidCidr(String cidr) {
        try {
            int[] p = parse(cidr);
            return p[1] >= 0 && p[1] <= 32;
        } catch (Exception e) {
            return false;
        }
    }

    // ── 내부 ────────────────────────────────────────────────────

    private static int[] parse(String cidr) {
        String[] parts = cidr.trim().split("/");
        if (parts.length != 2) throw new IllegalArgumentException("CIDR 형식 오류: " + cidr);
        long addr = ipToLong(parts[0]);
        int prefix = Integer.parseInt(parts[1].trim());
        if (prefix < 0 || prefix > 32) throw new IllegalArgumentException("prefix 범위 오류: " + prefix);
        return new int[]{(int) addr, prefix};
    }

    private static long maskFor(int prefix) {
        return prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
    }

    static long ipToLong(String ip) {
        String[] o = ip.trim().split("\\.");
        if (o.length != 4) throw new IllegalArgumentException("IPv4 형식 오류: " + ip);
        long result = 0;
        for (String part : o) {
            int v = Integer.parseInt(part);
            if (v < 0 || v > 255) throw new IllegalArgumentException("옥텟 범위 오류: " + ip);
            result = (result << 8) | v;
        }
        return result & 0xFFFFFFFFL;
    }

    private static String toIp(long value) {
        return ((value >> 24) & 0xFF) + "." + ((value >> 16) & 0xFF) + "."
                + ((value >> 8) & 0xFF) + "." + (value & 0xFF);
    }
}
