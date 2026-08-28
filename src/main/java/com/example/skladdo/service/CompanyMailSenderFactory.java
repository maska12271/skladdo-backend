package com.example.skladdo.service;

import com.example.skladdo.model.CompanySettings;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * Builds a {@link JavaMailSenderImpl} from SMTP parameters - either a company's stored settings (each
 * tenant sends its manufacturer emails through its own server) or the platform's own noreply@ sender
 * (see {@code PasswordResetService}). This is a plain object, constructed fresh per send/batch -
 * deliberately NOT a Spring-managed singleton, since there is no single sender shared by every caller.
 *
 * <p>Spring Boot's mail autoconfiguration only creates a default {@code JavaMailSender} bean when
 * {@code spring.mail.host} is set, which it never is here, so this hand-built sender is the only one in
 * play and there is no bean conflict.</p>
 */
@Component
public class CompanyMailSenderFactory {

    /**
     * @param settings          the company's settings (host/port/username/from must be populated)
     * @param decryptedPassword the SMTP password in plaintext (decrypted by the caller), may be null/blank
     */
    public JavaMailSenderImpl create(CompanySettings settings, String decryptedPassword) {
        return create(settings.getSmtpHost(), settings.getSmtpPort(), settings.getSmtpUsername(),
                decryptedPassword, settings.getSmtpUseTls());
    }

    /**
     * Builds a sender from raw SMTP parameters rather than a {@link CompanySettings} row - used for the
     * platform's own noreply@ sender (see {@code PasswordResetService}), which isn't tied to any tenant.
     */
    public JavaMailSenderImpl create(String host, Integer port, String username, String password, boolean useTls) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        if (port != null) {
            sender.setPort(port);
        }
        // Authenticate only when there is something to authenticate with. A blank username means the
        // server takes mail unauthenticated - the local Mailpit sink dev runs against does, and it does
        // not advertise AUTH at all, so announcing that we intend to authenticate fails the connection
        // before a single message is written. Any real host will have credentials and takes the branch
        // below.
        boolean authenticate = username != null && !username.isBlank();
        if (authenticate) {
            sender.setUsername(username);
            sender.setPassword(password);
        }
        sender.setDefaultEncoding("UTF-8");

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", String.valueOf(authenticate));
        props.put("mail.smtp.starttls.enable", String.valueOf(useTls));
        // Fail fast rather than hang the request thread if the SMTP server is unreachable.
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        return sender;
    }
}
