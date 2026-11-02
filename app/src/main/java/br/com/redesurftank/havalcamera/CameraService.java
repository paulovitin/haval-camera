package br.com.redesurftank.havalcamera;

import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class CameraService extends ICameraStream.Stub {
    private Process process;

    @Override public synchronized ParcelFileDescriptor start(String library, String config) {
        stop();
        try {
            String lib = new File(library).getCanonicalPath();
            String xml = new File(config).getCanonicalPath();
            if (!lib.startsWith("/data/app/br.com.redesurftank.havalcamera-")
                    || !lib.endsWith("/lib/arm64/libcamera_stream.so")
                    || !xml.equals("/data/data/br.com.redesurftank.havalcamera/files/input4.xml"))
                throw new SecurityException("Caminhos de captura inválidos");
            // The vendor namespace cannot load our library; run a private copy of the OEM client.
            File executable = new File(new File(xml).getParentFile(), "qcarcam");
            Files.copy(new File("/vendor/bin/qcarcam_test").toPath(), executable.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Os.chmod(executable.getPath(), 0700);
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            Process current;
            try {
                // shortcut: this OEM client and input 4 were verified on this head unit's firmware.
                current = new ProcessBuilder("/system/bin/sh", "-c",
                    "export LD_PRELOAD=\"$1\"; "
                    + "export LD_LIBRARY_PATH=/vendor/lib64:/system/lib64/vndk-28:/system/lib64/vndk-sp-28; "
                    + "exec \"$3\" -config=\"$2\" -noDisplay -dumpFrame=2 -nonInteractive 3>&1 1>&2",
                    "haval-camera", lib, xml, executable.getPath()).start();
            } catch (Exception e) { pipe[0].close(); pipe[1].close(); throw e; }
            process = current;
            new Thread(() -> {
                try (InputStream source = current.getInputStream();
                     OutputStream sink = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    byte[] buffer = new byte[65536];
                    int n;
                    while ((n = source.read(buffer)) != -1) sink.write(buffer, 0, n);
                } catch (Exception e) { Log.i("HavalCamera", "Stream closed: " + e); }
                finally { current.destroy(); }
            }, "CameraPipe").start();
            new Thread(() -> {
                try (BufferedReader log = new BufferedReader(new InputStreamReader(current.getErrorStream()))) {
                    String line;
                    while ((line = log.readLine()) != null) Log.w("HavalCameraNative", line);
                    Log.w("HavalCameraNative", "Native exit="+current.waitFor());
                } catch (Exception e) { Log.i("HavalCamera", "Log pipe closed"); }
            }, "CameraLog").start();
            return pipe[0];
        } catch (Exception e) { throw new IllegalStateException(e.getMessage(), e); }
    }

    @Override public synchronized void stop() {
        if (process != null) { process.destroy(); process = null; }
    }

    @Override public void destroy() { stop(); System.exit(0); }
}
