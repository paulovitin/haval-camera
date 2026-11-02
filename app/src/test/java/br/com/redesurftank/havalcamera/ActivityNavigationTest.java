package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class ActivityNavigationTest {
    @Test public void compiledScreenLayoutsInstallTheBackButton() throws Exception {
        String wrapper = methodCode(disassemble(BackNavigationActivity.class), "setContentView(");
        int button = wrapper.indexOf("ActivityNavigation.backButton:");
        int insertion = wrapper.indexOf("LinearLayout.addView:", button);
        assertTrue("wrapper must create the Back button", button >= 0);
        assertTrue("wrapper must insert it", insertion > button);
        assertTrue("wrapper must install the layout", wrapper.contains("Activity.setContentView:"));
        for (Class<?> screen : new Class<?>[]{CameraActivity.class, VideoGalleryActivity.class,
                VideoPlaybackActivity.class, VideoSettingsActivity.class}) {
            assertTrue(screen.getSimpleName(), methodCode(disassemble(screen), "onCreate(")
                    .contains("setContentView:(Landroid/view/View;)V"));
        }
    }

    @Test public void compiledFactoryConnectsVoltarToActivityFinish() throws Exception {
        String bytecode = disassemble(ActivityNavigation.class);
        String factory = methodCode(bytecode, "backButton(");
        assertTrue(factory.contains("configureBack:"));
        String adapter = methodCode(bytecode, "lambda$backButton$");
        assertTrue(adapter.contains("Button.setText:"));
        assertTrue(adapter.contains("Button.setOnClickListener:"));
        String configuration = methodCode(bytecode, "configureBack(");
        assertTrue(configuration.contains("String Voltar"));
        assertTrue(configuration.contains("backClick:"));
        assertTrue(disassembleVerbose(ActivityNavigation.class)
                .contains("REF_invokeVirtual android/app/Activity.finish:()V"));
    }

    private static String disassemble(Class<?> type) throws Exception {
        return disassemble(type, "-c", "-p");
    }

    private static String disassembleVerbose(Class<?> type) throws Exception {
        return disassemble(type, "-v", "-p");
    }

    private static String disassemble(Class<?> type, String first, String second) throws Exception {
        Path classFile = Files.createTempFile(type.getSimpleName(), ".class");
        try {
            try (InputStream input = type.getResourceAsStream(type.getSimpleName() + ".class")) {
                assertNotNull(input);
                Files.copy(input, classFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "javap").toString(),
                    first, second, classFile.toString()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(output, 0, process.waitFor());
            return output;
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    private static String methodCode(String disassembly, String signatureStart) {
        int start = disassembly.indexOf("  ", disassembly.indexOf("{")) + 2;
        start = disassembly.indexOf(signatureStart, start);
        assertTrue(signatureStart, start >= 0);
        int end = disassembly.indexOf("\n\n", start);
        return disassembly.substring(start, end < 0 ? disassembly.length() : end);
    }

    @Test public void sharedContentViewInstallationIsFinal() {
        assertTrue(Arrays.stream(BackNavigationActivity.class.getDeclaredMethods()).anyMatch(method ->
                method.getName().equals("setContentView")
                        && Arrays.equals(new Class<?>[]{View.class}, method.getParameterTypes())
                        && Modifier.isFinal(method.getModifiers())));
    }

    @Test public void everyScreenInheritsAutomaticBackLayout() {
        for (Class<?> screen : new Class<?>[]{CameraActivity.class, VideoGalleryActivity.class,
                VideoPlaybackActivity.class, VideoSettingsActivity.class}) {
            assertTrue(screen.getSimpleName(), BackNavigationActivity.class.isAssignableFrom(screen));
        }
    }

    @Test public void backButtonConfigurationLabelsAndFinishes() {
        AtomicInteger finishes = new AtomicInteger();
        AtomicReference<String> label = new AtomicReference<>();
        AtomicReference<View.OnClickListener> click = new AtomicReference<>();

        ActivityNavigation.configureBack(finishes::incrementAndGet, (text, listener) -> {
            label.set(text); click.set(listener);
        });

        assertEquals("Voltar", label.get());
        assertNotNull(click.get());
        click.get().onClick(null);
        assertEquals(1, finishes.get());
    }

    @Test public void backClickInvokesFinishOnce() {
        AtomicInteger finishes = new AtomicInteger();

        ActivityNavigation.backClick(finishes::incrementAndGet).onClick(null);

        assertEquals(1, finishes.get());
    }
}
