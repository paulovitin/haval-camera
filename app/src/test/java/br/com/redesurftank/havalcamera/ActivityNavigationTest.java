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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class ActivityNavigationTest {
    @Test public void compiledScreenLayoutsInstallTheBackButton() throws Exception {
        String wrapper = methodCode(disassemble(BackNavigationActivity.class), "setContentView(");
        List<String> code = instructions(wrapper);
        int button = instructionIndex(code, "ActivityNavigation.backButton:", 0);
        int firstInsertion = instructionIndex(code, "LinearLayout.addView:", 0);
        int secondInsertion = instructionIndex(code, "LinearLayout.addView:", firstInsertion + 1);
        assertTrue("wrapper must create the Back button", button >= 2);
        assertTrue("Back button must feed the first addView", firstInsertion > button);
        assertEquals("aload_2", opcode(code.get(button - 2))); // root
        assertEquals("aload_0", opcode(code.get(button - 1))); // Activity
        for (String line : code.subList(button + 1, firstInsertion)) {
            String operation = opcode(line);
            assertTrue("Back return value must stay on the stack: " + line,
                    Arrays.asList("new", "dup", "iconst_m1", "bipush", "sipush", "invokespecial")
                            .contains(operation));
            if (operation.equals("new") || operation.equals("invokespecial"))
                assertTrue(line, line.contains("LinearLayout$LayoutParams"));
        }
        assertTrue(code.get(firstInsertion).contains("addView:(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V"));
        assertTrue("content must be added after Back", secondInsertion > firstInsertion);
        assertEquals("aload_2", opcode(code.get(firstInsertion + 1)));
        assertEquals("aload_1", opcode(code.get(firstInsertion + 2)));
        assertTrue("wrapper must install the layout",
                instructionIndex(code, "Activity.setContentView:", secondInsertion + 1) > secondInsertion);
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
        assertEquals("one click listener is installed", 1,
                bytecode.lines().filter(line -> line.contains("Button.setOnClickListener:")).count());
        List<String> adapterCode = instructions(adapter);
        int setter = instructionIndex(adapterCode, "Button.setOnClickListener:", 0);
        assertTrue("configured listener must reach the Button", setter >= 2);
        assertEquals("aload_0", opcode(adapterCode.get(setter - 2))); // Button
        assertEquals("aload_2", opcode(adapterCode.get(setter - 1))); // configured click
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

    private static List<String> instructions(String method) {
        List<String> code = new ArrayList<>();
        for (String line : method.split("\\n"))
            if (line.matches("\\s*\\d+:.*")) code.add(line.trim());
        return code;
    }

    private static int instructionIndex(List<String> code, String target, int start) {
        for (int i = start; i < code.size(); i++)
            if (code.get(i).contains(target)) return i;
        return -1;
    }

    private static String opcode(String instruction) {
        return instruction.replaceFirst("^\\d+:\\s*", "").split("\\s+", 2)[0];
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
