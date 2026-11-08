package io.github.muntashirakon.adb;

import java.io.IOException;
import java.net.*;

public final class AdbSocketTest {
    private static int checks;
    private static void check(boolean v, String m) { checks++; if (!v) throw new AssertionError(m); }
    private static final class Probe extends Socket {
        SocketAddress source, destination; boolean closed, failBind, failConnect; int timeout;
        @Override public void bind(SocketAddress source) throws IOException {
            if (failBind) throw new IOException("bind failed"); this.source = source;
        }
        @Override public void connect(SocketAddress destination, int timeout) throws IOException {
            check(source != null || !((InetSocketAddress) destination).getAddress().isLoopbackAddress(), "Bind loopback before connecting");
            this.destination = destination; this.timeout = timeout;
            if (failConnect) throw new IOException("connect failed");
        }
        @Override public void close() { closed = true; }
    }
    public static void main(String[] args) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             Socket client = AdbSocket.connect("127.0.0.1", server.getLocalPort());
             Socket accepted = server.accept()) {
            check(client.getLocalAddress().isLoopbackAddress() && accepted.getInetAddress().isLoopbackAddress(), "Real TCP source and destination stay on loopback");
        }
        Probe local = new Probe(); AdbSocket.connect(local, "127.0.0.1", 37001);
        check(((InetSocketAddress) local.source).getPort() == 0 && local.timeout == 8000, "Use ephemeral loopback source and bounded TCP timeout");
        Probe remote = new Probe(); AdbSocket.connect(remote, "192.0.2.1", 37001);
        check(remote.source == null, "Preserve upstream behavior for other destinations");
        for (boolean bind : new boolean[]{true, false}) {
            Probe failure = new Probe(); failure.failBind = bind; failure.failConnect = !bind;
            try { AdbSocket.connect(failure, "127.0.0.1", 37001); throw new AssertionError("Failure ignored"); }
            catch (IOException expected) { check(failure.closed, "Close failed sockets at either stage"); }
        }
        System.out.println("PASS ADB socket assertions: " + checks);
    }
}
