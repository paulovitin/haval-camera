package br.com.redesurftank.havalcamera;

import static br.com.redesurftank.havalcamera.ActiveWifiAddress.select;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class ActiveWifiAddressTest {
    @Test public void selectsPrivateIpv4AfterUnusableAddresses() throws Exception {
        assertEquals("192.168.1.2", select(true, addresses(
                "0.0.0.0", "127.0.0.1", "8.8.8.8", "fd00::1", "192.168.1.2")).getHostAddress());
    }

    @Test public void acceptsEachPrivateIpv4Range() throws Exception {
        for (String address : Arrays.asList("10.0.0.2", "172.16.0.2", "172.31.255.254", "192.168.1.2"))
            assertEquals(address, select(true, addresses(address)).getHostAddress());
    }

    @Test public void selectsFirstEligibleAddressOnTheActiveWifi() throws Exception {
        assertEquals("10.0.0.2", select(true, addresses("10.0.0.2", "192.168.1.2")).getHostAddress());
    }

    @Test public void rejectsPrivateAddressesWithoutWifiTransport() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> select(false, addresses("10.0.0.2", "192.168.1.2")));
    }

    @Test public void rejectsLoopbackOnlyWifi() throws Exception {
        assertThrows(IllegalStateException.class, () -> select(true, addresses("127.0.0.1", "::1")));
    }

    @Test public void rejectsIpv6OnlyWifiIncludingPrivateAddresses() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> select(true, addresses("fd00::1", "fc00::1", "fe80::1", "2001:db8::1")));
    }

    @Test public void rejectsPublicAndNonPrivateIpv4Addresses() throws Exception {
        assertThrows(IllegalStateException.class, () -> select(true, addresses(
                "8.8.8.8", "0.0.0.0", "169.254.1.2", "100.64.0.2", "224.0.0.1",
                "172.15.255.254", "172.32.0.2", "192.169.1.2")));
    }

    @Test public void rejectsWifiWithoutAddresses() {
        assertThrows(IllegalStateException.class, () -> select(true, Collections.emptyList()));
    }

    private static List<InetAddress> addresses(String... values) throws Exception {
        InetAddress[] addresses = new InetAddress[values.length];
        for (int i = 0; i < values.length; i++) addresses[i] = InetAddress.getByName(values[i]);
        return Arrays.asList(addresses);
    }
}
