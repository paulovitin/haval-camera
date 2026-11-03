package br.com.redesurftank.havalcamera;
import java.io.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class UpdateDownloadTest {
    @Test public void streamsWithinBoundAndRejectsExtraByte() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        UpdateDownload.copy(new ByteArrayInputStream(new byte[]{1,2,3}),out,3);
        assertArrayEquals(new byte[]{1,2,3},out.toByteArray());
        try { UpdateDownload.copy(new ByteArrayInputStream(new byte[]{1,2,3,4}),out,3); fail("Oversized body accepted"); }
        catch (IOException expected) { }
    }
    @Test public void refusesWithoutConsentOrWhileBusy() {
        UpdateDownload.requireConsent(true,true);
        for (boolean[] flags : new boolean[][]{{false,true},{true,false},{false,false}}) {
            try { UpdateDownload.requireConsent(flags[0],flags[1]); fail("Unsafe download accepted"); }
            catch (IllegalStateException expected) { }
        }
    }
}
