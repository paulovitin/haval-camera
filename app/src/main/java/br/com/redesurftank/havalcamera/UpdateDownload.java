package br.com.redesurftank.havalcamera;
import java.io.*;
/** Streaming boundary shared by metadata and APK downloads. */
final class UpdateDownload {
    static void copy(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[32768]; long total=0; int count;
        while ((count=input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Download cancelado");
            total+=count; if (total>limit) throw new IOException("Resposta excede tamanho permitido");
            output.write(buffer,0,count);
        }
    }
    static void requireConsent(boolean confirmed, boolean idle) {
        if (!confirmed || !idle) throw new IllegalStateException("Confirme a atualização com captura e gravação ociosas");
    }
}
