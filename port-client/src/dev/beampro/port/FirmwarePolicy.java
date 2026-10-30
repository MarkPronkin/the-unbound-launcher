package dev.beampro.port;

import java.util.Locale;

/** Offline, exact stable-release checks. Never starts OTA or changes a glasses setting. */
public final class FirmwarePolicy {
    public static final String UPDATER = "https://www.xreal.com/ota/";
    public static final String ROG_UPDATER = "https://rog.asus.com/glasses/rog-xreal-r1/helpdesk_bios/";
    public static final String VERIFIED_ON = "2026-10-29";
    public static final String ROG_VERIFIED_ON = "2026-10-30";

    public interface Reader { String read(int component) throws Exception; }

    public static final class Result {
        public final boolean supported;
        public final String model, detected, recommended, detail, updater;
        Result(boolean supported, String model, String detected, String recommended, String detail) {
            this(supported,model,detected,recommended,detail,UPDATER);
        }
        Result(boolean supported, String model, String detected, String recommended, String detail, String updater) {
            this.supported=supported;this.model=model;this.detected=detected;
            this.recommended=recommended;this.detail=detail;this.updater=updater;
        }
    }

    static final class Profile {
        final String model, updater;
        final int[] components;
        final String[] versions;
        Profile(String model, int[] components, String... versions) {
            this(model,UPDATER,components,versions);
        }
        Profile(String model, String updater, int[] components, String... versions) {
            this.model=model;this.updater=updater;this.components=components;this.versions=versions;
        }
    }

    // XREAL's public stable updater, checked on VERIFIED_ON:
    // https://app-api.xreal.com/api/nebula/v1/isc/device/package
    //   ?packageName=ai.nreal.web&hardwareCode=<native model type>&versionCode=1
    // These are pinned recommendations, not a minimum-version test. A newer stable
    // release must be reviewed before changing this table. No network is needed.
    // OTA reader IDs: 1 MCU, 2 DP, 3 DSP, 6 Pilot/software, 7 LUT, 9 OSD.
    // ID 6 maps to read-only USB command 0x1213, payload [2] in libnr_glasses_api;
    // XREAL's web updater uses the same command for getFirmwareVersionInPilot.
    static Profile profile(int type) {
        switch(type) {
            case 2: return new Profile("XREAL Air", new int[]{1,2,3}, "07.1.02.387_20240428", "1140", "ROME_IA8201_REL_2122_9_16_Kn_indie");
            case 3: return new Profile("XREAL Air 2 Pro", new int[]{1,2,3}, "09.1.00.180_20240507", "1140", "09.A.00.001_20230615");
            case 4: return new Profile("XREAL Air 2", new int[]{1,2,3}, "09.1.00.180_20240507", "1140", "09.A.00.001_20230615");
            case 5: return new Profile("XREAL Air 2 Ultra", new int[]{1,2,3}, "12.1.00.498_20241115", "1140", "09.A.00.001_20230615");
            case 6: return new Profile("XREAL One Pro", new int[]{6,1,3},
                    "1.9.1.20260914175625", "15.1.03.603_20260914", "15.A.01.097_20250430");
            case 7: return new Profile("XREAL One", new int[]{6,1,3},
                    "1.9.1.20260914175625", "15.1.03.603_20260914", "15.A.00.069_20241211");
            case 9: return new Profile("XREAL 1S", new int[]{6,1,3},
                    "1.9.1.20260914175625", "15.1.03.603_20260914", "15.A.01.097_20250430");
            case 10: case 11: return new Profile(type==10?"xbx a01":"xbx a01+", new int[]{1,2,7,9},
                    "19.1.01.125_20260624", "001C", "0425103S0425103H00425103H10425103C0425103E0425103G", "OSD.V0013");
            case 20: return new Profile("XREAL 1 Pro+", new int[]{6,1,3},
                    "2.0.0.20260920102250", "15.1.03.643_20261010", "15.A.01.103_20260710");
            // ASUS stable package ASUS_R1_MCM103.104.zip (2026-10-08), checked on
            // ROG_VERIFIED_ON. The .104 is the dock; the glasses are MCM103.
            // MCU: R1/Firmware/ASUS_ROG_XREAL_R1_MCM103.bin header; Pilot:
            // embedded pilot/pilot_version.txt; DSP: R1/Firmware/glasses.cfg.
            // This policy identity must never be passed to native SDK startup.
            case 1003: return new Profile("ROG XREAL R1", ROG_UPDATER, new int[]{1,6,3},
                    "MCM103", "1.9.0.20260709183133", "15.A.01.097_20250430");
            default: return null;
        }
    }

    public static Result check(int type, Reader reader) {
        Profile p=profile(type);
        if(p==null) return unavailable(type,"This glasses model has no verified stable firmware recommendation.");
        String primary="Not readable";
        for(int i=0;i<p.components.length;i++) {
            int component=p.components[i];
            String actual;
            try { actual=clean(reader.read(component)); }
            catch(Exception error) {
                return new Result(false,p.model,"Not readable",p.versions[i]+" · stable",
                        "Could not read the glasses’ "+label(component)+" firmware. Compatibility could not be verified.",p.updater);
            }
            if(i==0) primary=actual.isEmpty()?"Not readable":actual;
            if(!matches(actual,p.versions[i])) return new Result(false,p.model,
                    actual.isEmpty()?"Not readable":actual, p.versions[i]+" · stable",
                    label(component)+" firmware "+(actual.isEmpty()?"could not be read.":"does not match the recommended stable release.")
                    +" Only the exact recommended release is supported.",p.updater);
        }
        return new Result(true,p.model,primary,p.versions[0]+" · stable","Firmware matches the recommended stable release.",p.updater);
    }

    public static Result unavailable(int type, String detail) {
        Profile p=profile(type);
        String model=p==null?(type==1003?"ROG XREAL R1":"XREAL glasses"):p.model;
        return new Result(false,model,"Not verified",p==null?"No verified release":p.versions[0]+" · stable",detail,p==null?UPDATER:p.updater);
    }

    static String label(int component) {
        switch(component) {case 1:return "MCU";case 2:return "DP";case 3:return "DSP";
            case 6:return "Software";case 7:return "LUT";case 9:return "OSD";default:return "Unknown";}
    }

    static String clean(String value) {
        if(value==null)return "";
        String s=value.trim();
        // Native strings occasionally retain terminal NUL bytes. Do not truncate
        // embedded data or remove beta/build suffixes: those must fail the check.
        while(s.endsWith("\u0000"))s=s.substring(0,s.length()-1).trim();
        return s.length()>256?s.substring(0,256):s;
    }

    static boolean matches(String actual,String expected) {
        String s=clean(actual).toLowerCase(Locale.ROOT), e=expected.toLowerCase(Locale.ROOT);
        if(s.endsWith(".bin"))s=s.substring(0,s.length()-4);
        if(s.startsWith("pilot_"))s=s.substring(6);
        if(s.startsWith("pilot."))s=s.substring(6);
        if(s.equals(e))return true;
        // Some MCU/DSP readers omit only the packaging date; keep every version
        // component and every prerelease suffix significant.
        int date=e.lastIndexOf('_');
        return date>0&&e.substring(date+1).matches("[0-9]{8}")&&s.equals(e.substring(0,date));
    }
    private FirmwarePolicy() {}
}
