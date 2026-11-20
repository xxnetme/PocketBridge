package com.local.wififiles;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Address policy shared by the listener, TLS identity and the addresses shown in Android. */
final class LanAddresses {
    private LanAddresses() {}

    static List<InetAddress> advertised() {
        LinkedHashSet<InetAddress> result = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return Collections.emptyList();
            List<NetworkInterface> ordered = Collections.list(interfaces);
            ordered.sort((a, b) -> Integer.compare(priority(a), priority(b)));
            for (NetworkInterface ni : ordered) {
                try {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (InetAddress ip : Collections.list(ni.getInetAddresses())) {
                        if (ip instanceof Inet4Address && !ip.isLoopbackAddress() && allowed(ip)) result.add(ip);
                    }
                } catch (SocketException ignored) {
                    // An interface can disappear while the network is changing.
                }
            }
        } catch (SocketException | SecurityException ignored) { }
        return Collections.unmodifiableList(new ArrayList<>(result));
    }

    /** Only show live addresses covered by the certificate of the running listener. */
    static List<String> httpsUrls(List<InetAddress> current, List<InetAddress> certified, int port) {
        List<String> urls = new ArrayList<>();
        for (InetAddress ip : current) {
            if (certified.contains(ip)) urls.add("https://" + ip.getHostAddress() + ":" + port);
        }
        return urls;
    }

    /** Loopback, RFC1918, CGNAT 100.64/10, IPv6 ULA and link-local. */
    static boolean allowed(InetAddress ip) {
        if (ip.isAnyLocalAddress() || ip.isMulticastAddress()) return false;
        if (ip.isLoopbackAddress() || ip.isSiteLocalAddress()) return true;
        byte[] b = ip.getAddress();
        if (b.length == 4) {
            int second = b[1] & 0xff;
            return (b[0] & 0xff) == 100 && second >= 64 && second <= 127;
        }
        if (b.length == 16) {
            int first = b[0] & 0xff;
            return first == 0xfc || first == 0xfd || (first == 0xfe && (b[1] & 0xc0) == 0x80);
        }
        return false;
    }

    private static int priority(NetworkInterface ni) {
        String name = ni.getName().toLowerCase(Locale.ROOT);
        if (name.contains("wlan") || name.startsWith("ap") || name.startsWith("wifi") || name.startsWith("softap")) return 0;
        return name.startsWith("eth") ? 1 : 2;
    }
}
