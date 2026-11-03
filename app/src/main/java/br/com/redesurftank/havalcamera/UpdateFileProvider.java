package br.com.redesurftank.havalcamera;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import org.xmlpull.v1.XmlPullParser;

/** Platform-only FileProvider: one cache directory, APKs only, URI grants, no writes. */
public final class UpdateFileProvider extends ContentProvider {
    public static final String AUTHORITY = "br.com.redesurftank.havalcamera.updates";
    public static final String MIME = "application/vnd.android.package-archive";
    @Override public boolean onCreate() {
        // Fail closed if the packaged path configuration ever broadens.
        try (XmlResourceParser xml = getContext().getResources().getXml(R.xml.update_paths)) {
            int roots=0;
            for (int event=xml.next(); event!=XmlPullParser.END_DOCUMENT; event=xml.next()) {
                if (event!=XmlPullParser.START_TAG || "paths".equals(xml.getName())) continue;
                if (!"cache-path".equals(xml.getName()) || !"updates".equals(xml.getAttributeValue(null,"name"))
                        || !"updates/".equals(xml.getAttributeValue(null,"path"))) throw new SecurityException("Caminho OTA inválido");
                roots++;
            }
            if (roots!=1) throw new SecurityException("Cache OTA inválido");
            return true;
        } catch (Exception e) { throw new IllegalStateException("Configuração OTA inválida",e); }
    }
    public static Uri uriFor(Context context, File apk) throws IOException {
        File resolved=UpdateCache.resolve(context.getCacheDir(),apk.getName());
        if (!resolved.equals(apk.getCanonicalFile())) throw new FileNotFoundException("Fora do cache OTA");
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("updates").appendPath(apk.getName()).build();
    }
    private File file(Uri uri) throws FileNotFoundException {
        if (!"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority()) || uri.getQuery()!=null || uri.getFragment()!=null
                || uri.getPathSegments().size()!=2 || !"updates".equals(uri.getPathSegments().get(0)))
            throw new FileNotFoundException("URI OTA inválida");
        try { return UpdateCache.resolve(getContext().getCacheDir(),uri.getLastPathSegment()); }
        catch (IOException e) { throw new FileNotFoundException("Caminho OTA inválido"); }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        UpdateCache.requireReadOnly(mode); return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { return MIME; }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        try {
            File file=file(uri);
            String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
            MatrixCursor cursor=new MatrixCursor(columns); Object[] row=new Object[columns.length];
            for (int i=0;i<columns.length;i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i]=file.getName();
                else if (OpenableColumns.SIZE.equals(columns[i])) row[i]=file.length();
            }
            cursor.addRow(row); return cursor;
        } catch (FileNotFoundException e) { throw new IllegalArgumentException(e); }
    }
    @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException("Somente leitura"); }
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args) { throw new UnsupportedOperationException("Somente leitura"); }
    @Override public int delete(Uri uri,String selection,String[] args) { throw new UnsupportedOperationException("Somente leitura"); }
}
