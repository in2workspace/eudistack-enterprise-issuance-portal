package es.in2.issuanceportal.journey.infrastructure.mail;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** The pieces the SMTP adapter tests share. */
final class SmtpTestSupport {

    static final String FROM = "noreply@mail.stg.eudistack.net";

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
}
