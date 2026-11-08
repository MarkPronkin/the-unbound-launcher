// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0
package io.github.muntashirakon.adb;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Keep same-phone ADB replies on loopback even when the default route is a VPN. */
final class AdbSocket {
    private AdbSocket() {}

    static Socket connect(String host, int port) throws IOException {
        return connect(new Socket(), host, port);
    }

    static Socket connect(Socket socket, String host, int port) throws IOException {
        try {
            if ("127.0.0.1".equals(host)) socket.bind(new InetSocketAddress("127.0.0.1", 0));
            socket.connect(new InetSocketAddress(host, port), 8000);
            return socket;
        } catch (IOException | RuntimeException error) {
            try { socket.close(); } catch (IOException ignored) {}
            throw error;
        }
    }
}
