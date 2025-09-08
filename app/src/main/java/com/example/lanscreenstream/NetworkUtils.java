package com.example.lanscreenstream;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

public class NetworkUtils {

    public static String getLocalIp(Context ctx) {
        // Try the active network first (Android 6+)
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network active = cm.getActiveNetwork();
                if (active != null) {
                    LinkProperties lp = cm.getLinkProperties(active);
                    if (lp != null) {
                        List<LinkAddress> addrs = lp.getLinkAddresses();
                        for (LinkAddress la : addrs) {
                            InetAddress a = la.getAddress();
                            if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                                return a.getHostAddress();
                            }
                        }
                    }
                }

                // Fallback: iterate all networks, prefer WIFI/ETHERNET
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                    if (nc == null) continue;
                    boolean good = nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                    if (!good) continue;
                    LinkProperties lp = cm.getLinkProperties(n);
                    if (lp == null) continue;
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        InetAddress a = la.getAddress();
                        if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                            return a.getHostAddress();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        // Last resort: enumerate interfaces
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface nif : Collections.list(ifaces)) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                for (InetAddress a : Collections.list(addrs)) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {}

        return "0.0.0.0";
    }
}
