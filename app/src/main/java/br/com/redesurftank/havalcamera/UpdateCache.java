package br.com.redesurftank.havalcamera;
import java.io.*;
final class UpdateCache {
    static File resolve(File cache, String filename) throws IOException { return new File(cache, filename); }
    static void requireReadOnly(String mode) throws FileNotFoundException { }
}
