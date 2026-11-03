package br.com.redesurftank.havalcamera;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/** Selects only a private IPv4 belonging to Android's active Wi-Fi network. */
public final class ActiveWifiAddress {
    private ActiveWifiAddress() { }

    public static InetAddress from(Context context) {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        Network active = connectivity == null ? null : connectivity.getActiveNetwork();
        if (active == null) throw new IllegalStateException("Nenhuma rede ativa disponível");
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(active);
        if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            throw new IllegalStateException("A rede ativa não é Wi-Fi");
        LinkProperties properties = connectivity.getLinkProperties(active);
        if (properties == null) throw new IllegalStateException("Endereços do Wi-Fi ativo indisponíveis");
        List<InetAddress> addresses = new ArrayList<>();
        for (LinkAddress link : properties.getLinkAddresses()) addresses.add(link.getAddress());
        return select(true, addresses);
    }

    public static InetAddress select(boolean wifiTransport, List<InetAddress> addresses) {
        if (!wifiTransport) throw new IllegalStateException("A rede ativa não é Wi-Fi");
        for (InetAddress address : addresses)
            if (address instanceof Inet4Address && !address.isLoopbackAddress()
                    && address.isSiteLocalAddress()) return address;
        throw new IllegalStateException("IPv4 privado não encontrado no Wi-Fi ativo");
    }
}
