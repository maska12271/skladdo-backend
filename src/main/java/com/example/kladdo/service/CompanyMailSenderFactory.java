package com.example.kladdo.service;

import com.example.kladdo.model.CompanySettings;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * Builds a {@link JavaMailSenderImpl} from a company's stored SMTP settings, with the password already
 * decrypted by the caller. This is a plain object, constructed fresh per send/batch - deliberately NOT a
 * Spring-managed singleton, because there is no single app-wide mail server: each tenant sends through
 * its own.
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
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.getSmtpHost());
        if (settings.getSmtpPort() != null) {
            sender.setPort(settings.getSmtpPort());
        }
        sender.setUsername(settings.getSmtpUsername());
        sender.setPassword(decryptedPassword);
        sender.setDefaultEncoding("UTF-8");

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", String.valueOf(settings.getSmtpUseTls()));
        // Fail fast rather than hang the request thread if the SMTP server is unreachable.
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        return sender;
    }
}
