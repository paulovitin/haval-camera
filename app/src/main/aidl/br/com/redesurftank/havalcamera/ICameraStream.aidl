package br.com.redesurftank.havalcamera;
import android.os.ParcelFileDescriptor;
interface ICameraStream {
    ParcelFileDescriptor start(String library, String config) = 0;
    void stop() = 1;
    void destroy() = 16777114;
}
