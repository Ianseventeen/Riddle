package com.riddles.riddles_backend;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Map;

@RestController
@CrossOrigin(origins = "*")
public class IpController {

    @GetMapping("/api/ip")
    public Map<String, String> getLocalIp() {
        String ip = getLanIpAddress();
        return Map.of("ip", ip);
    }

    private String getLanIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;

                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    String hostAddr = addr.getHostAddress();
                    if (!addr.isLoopbackAddress() && !hostAddr.contains(":")) {
                        if (hostAddr.startsWith("192.168.") || hostAddr.startsWith("10.") || hostAddr.startsWith("172.")) {
                            return hostAddr;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "localhost";
    }
}
