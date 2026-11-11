package es.in2.issuanceportal.journey.infrastructure.mail;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

/** The pieces the SMTP adapter tests share. */
final class SmtpTestSupport {

    static final String FROM = "noreply@mail.stg.eudistack.net";
    static final String ASSETS = "https://assets.example.org/tenants";

    private SmtpTestSupport() {
    }

    /** A sender towards a plain SMTP server (no TLS, no login), with the timeouts the service configures. */
    static JavaMailSenderImpl senderTo(int port) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("127.0.0.1");
        sender.setPort(port);
        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.smtp.auth", "false");
        properties.put("mail.smtp.starttls.enable", "false");
        properties.put("mail.smtp.connectiontimeout", "5000");
        properties.put("mail.smtp.timeout", "5000");
        properties.put("mail.smtp.writetimeout", "5000");
        return sender;
    }

    /** The HTML renderer of the code email, with no tenant branded. */
    static EmailCodeHtml html() {
        return html(Map.of());
    }

    /** The HTML renderer of the code email, with the assets of {@link #ASSETS} and the given tenants branded. */
    static EmailCodeHtml html(Map<String, EmailBrandingProperties.TenantEmailBranding> tenants) {
        return new EmailCodeHtml(new EmailBrandingResolver(new EmailBrandingProperties(ASSETS, tenants)));
    }

    /** An empty MIME message, for a mocked sender. */
    static MimeMessage emptyMessage() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    /** A port where nothing listens. */
    static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    /** Accepts connections and sends nothing back: the peer of an SMTP server that hangs. */
    static final class SilentServer implements AutoCloseable {

        private final ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        private final List<Socket> accepted = new ArrayList<>();
        private final Thread acceptor;

        SilentServer() throws IOException {
            acceptor = Thread.ofVirtual().start(() -> {
                try {
                    while (!server.isClosed()) {
                        Socket socket = server.accept();
                        synchronized (accepted) {
                            accepted.add(socket);
                        }
                    }
                } catch (IOException _) {
                    // Closed by the test.
                }
            });
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            server.close();
            synchronized (accepted) {
                for (Socket socket : accepted) {
                    socket.close();
                }
            }
            acceptor.interrupt();
        }
    }

    /**
     * An SMTP server that does not offer STARTTLS and records every command it receives, to check what a client
     * sends before giving up.
     */
    static final class PlainSmtpServer implements AutoCloseable {

        private final ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        private final List<String> commands = new CopyOnWriteArrayList<>();
        private final Thread acceptor;

        PlainSmtpServer() throws IOException {
            acceptor = Thread.ofVirtual().start(() -> {
                try {
                    while (!server.isClosed()) {
                        Socket socket = server.accept();
                        Thread.ofVirtual().start(() -> serve(socket));
                    }
                } catch (IOException _) {
                    // Closed by the test.
                }
            });
        }

        private void serve(Socket socket) {
            try (socket;
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 OutputStream out = socket.getOutputStream()) {
                reply(out, "220 plain.test ESMTP");
                for (String line = in.readLine(); line != null; line = in.readLine()) {
                    commands.add(line);
                    String verb = line.toUpperCase(java.util.Locale.ROOT);
                    if (verb.startsWith("EHLO")) {
                        reply(out, "250-plain.test\r\n250 AUTH PLAIN LOGIN");
                    } else if (verb.startsWith("QUIT")) {
                        reply(out, "221 bye");
                        return;
                    } else {
                        reply(out, "250 ok");
                    }
                }
            } catch (IOException _) {
                // The client left.
            }
        }

        private static void reply(OutputStream out, String text) throws IOException {
            out.write((text + "\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.flush();
        }

        int port() {
            return server.getLocalPort();
        }

        List<String> commands() {
            return commands;
        }

        @Override
        public void close() throws IOException {
            server.close();
            acceptor.interrupt();
        }
    }
}
