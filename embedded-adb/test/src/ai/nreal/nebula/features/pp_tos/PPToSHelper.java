package ai.nreal.nebula.features.pp_tos;

/** Isolated substitute for the original policy-version callback; never shipped. */
public final class PPToSHelper {
    private static final PPToSHelper INSTANCE = new PPToSHelper();
    public int agreements;
    public static PPToSHelper getInstance() { return INSTANCE; }
    public void firstAgree() { agreements++; }
}
