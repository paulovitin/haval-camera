package br.com.redesurftank.havalcamera;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.*;

/** Compiled Android call-path check; behavior is exercised by UpdateIdleFlowTest. */
public class UpdateSettingsWiringTest {
    @Test public void settingsUsesGuidedGateForDownloadAndInstallAndCancelsOnPause() throws Exception {
        assertTrue(UpdateIdleFlow.Host.class.isAssignableFrom(VideoSettingsActivity.class));
        String activity=code(VideoSettingsActivity.class);
        assertTrue(method(activity,"downloadUpdate(").contains("UpdateIdleFlow.whenIdle:"));
        assertTrue(method(activity,"installUpdate(").contains("UpdateIdleFlow.whenIdle:"));
        assertTrue(method(activity,"onPause(").contains("UpdateIdleFlow.cancel:"));
        assertFalse(method(activity,"checkForUpdate(").contains("updateIdle:"));
        String offer=method(activity,"offerEndViewing(");
        assertTrue(offer.contains("String Encerrar visualização"));
        assertTrue(offer.contains("setPositiveButton:"));
        assertTrue(method(activity,"schedulePoll(").contains("Handler.postDelayed:"));
        assertTrue(method(activity,"stopPolling(").contains("Handler.removeCallbacks:"));
        assertTrue(method(activity,"endViewing(").contains("LocalBinder.closeViewingForUpdate:"));
    }
    @Test public void binderClosesOnlyViewingThroughRecordingPreservingEvent() throws Exception {
        String binder=method(code(TripVideoService.LocalBinder.class),"closeViewingForUpdate(");
        assertTrue(binder.contains("FrameRouter.closePreviews:"));
        assertTrue(binder.contains("ServiceStateReducer$Event.viewerDismissed:"));
        assertFalse(binder.contains("stopRecording")); assertFalse(binder.contains("stopManual"));
        assertFalse(binder.contains("stopAutomatic")); assertFalse(binder.contains("forceStopCapture"));
    }
    private static String code(Class<?> type) throws Exception {
        Path file=Files.createTempFile("update-wiring-", ".class");
        try {
            try (InputStream input=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) {
                assertNotNull(input); Files.copy(input,file,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","javap").toString(),
                    "-c","-p",file.toString()).redirectErrorStream(true).start();
            String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            assertEquals(output,0,process.waitFor()); return output;
        } finally { Files.deleteIfExists(file); }
    }
    private static String method(String code,String name) {
        int start=code.indexOf(name); assertTrue("Missing compiled method: "+name,start>=0);
        int end=code.indexOf("\n\n",start); return code.substring(start,end<0?code.length():end);
    }
}
