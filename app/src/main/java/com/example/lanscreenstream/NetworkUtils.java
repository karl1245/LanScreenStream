package com.example.lanscreenstream;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

// … package & imports unchanged …

public class NetworkUtils {
    private static final String TAG = "NetworkUtils";

    public static String getLocalIp(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network active = cm.getActiveNetwork();
                if (active != null) {
                    LinkProperties lp = cm.getLinkProperties(active);
                    if (lp != null) {
                        for (LinkAddress la : lp.getLinkAddresses()) {
                            InetAddress a = la.getAddress();
                            if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                                String ip = a.getHostAddress();
                                Log.i(TAG, "Active network IPv4: " + ip);
                                return ip;
                            }
                        }
                    }
                }

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
                            String ip = a.getHostAddress();
                            Log.i(TAG, "Fallback network IPv4: " + ip);
                            return ip;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "getLocalIp via ConnectivityManager failed", t);
        }

        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface nif : Collections.list(ifaces)) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                for (InetAddress a : Collections.list(addrs)) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        Log.i(TAG, "Interface IPv4: " + nif.getName() + " -> " + ip);
                        return ip;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "getLocalIp via interfaces failed", t);
        }

        Log.e(TAG, "No non-loopback IPv4 found; returning 0.0.0.0");
        return "0.0.0.0";
    }
}

