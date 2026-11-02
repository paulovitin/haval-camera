package br.com.redesurftank.havalcamera;
import java.io.*;
final class UpdateCache {
    static File resolve(File cache, String filename) throws IOException {
        if (filename == null || !filename.matches("update-[A-Za-z0-9-]+\\.apk")) throw new FileNotFoundException("APK inválido");
        File base=cache.getCanonicalFile(), root=new File(base,"updates");
        if (!root.getCanonicalFile().equals(root)) throw new FileNotFoundException("Cache redirecionado");
        File file=new File(root,filename);
        if (!file.getCanonicalFile().equals(file)) throw new FileNotFoundException("APK redirecionado");
        return file;
    }
    static void requireReadOnly(String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Somente leitura");
    }
}
