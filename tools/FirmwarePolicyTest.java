package dev.beampro.port;

public final class FirmwarePolicyTest {
    static int checks;
    static void require(boolean value,String detail){checks++;if(!value)throw new AssertionError(detail);}
    public static void main(String[] args) {
        for(int type:new int[]{2,3,4,5,6,7,9,10,11,20,1003}) {
            FirmwarePolicy.Profile p=FirmwarePolicy.profile(type);
            int[] reads={0};
            FirmwarePolicy.Result r=FirmwarePolicy.check(type,c->{
                require(c==p.components[reads[0]],"Read components in the declared order");
                return p.versions[reads[0]++];
            });
            require(r.supported&&reads[0]==p.versions.length,"All recommended components match for model "+type);
            for(int mismatch=0;mismatch<p.components.length;mismatch++) {
                final int failed=mismatch;
                r=FirmwarePolicy.check(type,c->{
                    for(int i=0;i<p.components.length;i++)if(c==p.components[i])return i==failed?p.versions[i]+"-beta":p.versions[i];
                    throw new AssertionError("Unexpected component");
                });
                require(!r.supported,"Reject a beta suffix on every component of model "+type);
            }
        }
        for(String bad:new String[]{"1.8.0.20260914175625","2.0.0.20260901182643","1.9.2.20260914175625",
                "1.9.1","1.9.1.20260914175626","1.9.1.20260914175625-rc1","stable","",null})
            require(!FirmwarePolicy.check(7,c->bad).supported,"Reject older, newer, beta, partial and unreadable software: "+bad);
        require(FirmwarePolicy.matches(" pilot_1.9.1.20260914175625.bin\u0000 ","1.9.1.20260914175625"),"Accept native padding and packaging wrappers");
        require(FirmwarePolicy.matches("15.1.03.603","15.1.03.603_20260914"),"Accept a reader that omits the packaging date");
        require(!FirmwarePolicy.matches("15.1.03.603_20260915","15.1.03.603_20260914"),"A different date is not silently ignored");
        require(!FirmwarePolicy.matches("15.1.03.603-beta","15.1.03.603_20260914"),"Do not erase prerelease suffixes");
        require(!FirmwarePolicy.check(7,c->{throw new java.io.IOException("unplugged");}).supported,"Read errors fail closed");
        FirmwarePolicy.Result partial=FirmwarePolicy.check(7,c->{if(c==6)return "1.9.1.20260914175625";throw new java.io.IOException("MCU unavailable");});
        require(partial.detected.equals("Not readable")&&partial.detail.contains("MCU"),"A later component read failure is not displayed as a matching software version");
        for(int type:new int[]{-1,0,1,8})require(!FirmwarePolicy.check(type,c->{throw new AssertionError("No blind commands for an unknown model");}).supported,"Unknown model has no automatic approval");
        FirmwarePolicy.Profile rog=FirmwarePolicy.profile(1003);
        require(rog.model.equals("ROG XREAL R1")&&rog.versions[0].equals("MCM103"),"R1 has its own ASUS glasses release, separate from the dock");
        for(String bad:new String[]{"MCM102","MCM104","MCM103-beta","15.1.03.603_20260914","",null})
            require(!FirmwarePolicy.check(1003,c->bad).supported,"Do not accept another release or One Pro firmware for R1: "+bad);
        FirmwarePolicy.Result r1=FirmwarePolicy.check(1003,c->c==1?"MCM103":c==6?"1.9.0.20260709183133":"15.A.01.097_20250430");
        require(r1.supported&&r1.detected.equals("MCM103")&&r1.updater.equals(FirmwarePolicy.ROG_UPDATER),"R1 reads all ASUS package components and links to ASUS");
        require(FirmwarePolicy.unavailable(1003,"Read timed out").updater.equals(FirmwarePolicy.ROG_UPDATER),"R1 read timeouts retain the ASUS updater");
        require(FirmwarePolicy.check(1003,c->{throw new java.io.IOException();}).updater.equals(FirmwarePolicy.ROG_UPDATER),"R1 read errors retain the ASUS updater");
        require(FirmwarePolicy.unavailable(6,"Read timed out").updater.equals(FirmwarePolicy.UPDATER),"XREAL models retain the XREAL updater");
        require(FirmwarePolicy.profile(20).versions[0].startsWith("2.0.0."),"Model-specific stable 2.0.0 is not confused with One's beta");
        System.out.println("PASS firmware policy assertions: "+checks);
    }
}
