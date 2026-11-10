package app.foldfix;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * Created once per process, before the service or any screen. It marks that moment in the history
 * ({@link FoldLog}), which is kept in memory and so begins there, and knows which build this is.
 */
public class App extends Application {
    /**
     * SHA-256 of the certificate the releases are signed with. Not a secret: the release job's
     * log prints it, and release notes quote it.
     */
    static final String RELEASE_CERT_SHA256 = "41f87594fad12af529af92f99bd25c8309020fc5d681acdf9d2fc6c53bdb57e3";

    @Override
    public void onCreate() {
        super.onCreate();
        String version = version(this);
        FoldLog.i("FoldFix " + (version != null ? version : "(unknown version)") + " started");
    }

    /**
     * The installed version, for example "1.0.2 (3)", or null. Read from the package manager, so
     * it is what is installed, whatever its version is called.
     */
    static String version(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), PackageManager.PackageInfoFlags.of(0));
            return p.versionName + " (" + p.getLongVersionCode() + ")";
        } catch (Exception e) {
            return null;
        }
    }

    /** Version and who signed the installed app, for example "1.0.2 (3), official release". */
    static String describeBuild(Context c) {
        String version = version(c);
        if (version == null) return "Unknown";
        // Looked up separately, so that a failure here still leaves the version.
        try {
            SigningInfo info = c.getPackageManager().getPackageInfo(c.getPackageName(), PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_SIGNING_CERTIFICATES)).signingInfo;
            // The current signer only, not earlier keys it may have been rotated from.
            Signature[] signers = info == null ? null : info.getApkContentsSigners();
            if (signers == null || signers.length == 0) return version;
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray())) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return version + (signers.length == 1 && RELEASE_CERT_SHA256.contentEquals(hex)
                    ? ", official release" : ", self-built (signed with another key)");
        } catch (Exception e) {
            return version;  // better no claim than a wrong one
        }
    }
}
