package dev.beampro.adb;

import android.content.Context;
import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.concurrent.TimeUnit;

final class LocalAdb extends AbsAdbConnectionManager {
    private final AdbIdentity identity;
    LocalAdb(Context context) throws Exception {
        identity = AdbIdentity.load(context);
        setApi(android.os.Build.VERSION.SDK_INT);
        setHostAddress("127.0.0.1");
        setTimeout(8, TimeUnit.SECONDS);
    }
    @Override protected PrivateKey getPrivateKey() { return identity.key; }
    @Override protected Certificate getCertificate() { return identity.certificate; }
    @Override protected String getDeviceName() { return "unbound-launcher@android"; }
}
