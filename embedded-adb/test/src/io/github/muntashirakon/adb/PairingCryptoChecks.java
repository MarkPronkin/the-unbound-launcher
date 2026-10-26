package io.github.muntashirakon.adb;

import java.util.Arrays;

public final class PairingCryptoChecks {
    public static void verify() {
        byte[] password = "test-pairing-password".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        PairingAuthCtx alice = PairingAuthCtx.createAlice(password), bob = PairingAuthCtx.createBob(password);
        if (alice == null || bob == null || !alice.initCipher(bob.getMsg()) || !bob.initCipher(alice.getMsg())) throw new AssertionError("SPAKE exchange");
        byte[] message = "authenticated peer info".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (!Arrays.equals(message, bob.decrypt(alice.encrypt(message)))) throw new AssertionError("SPAKE authenticated encryption");
        alice.destroy(); bob.destroy();
        PairingAuthCtx wrongAlice = PairingAuthCtx.createAlice(password), wrongBob = PairingAuthCtx.createBob(new byte[]{7, 8});
        wrongAlice.initCipher(wrongBob.getMsg()); wrongBob.initCipher(wrongAlice.getMsg());
        if (wrongBob.decrypt(wrongAlice.encrypt(message)) != null) throw new AssertionError("Incorrect password accepted");
        wrongAlice.destroy(); wrongBob.destroy();
    }
}
