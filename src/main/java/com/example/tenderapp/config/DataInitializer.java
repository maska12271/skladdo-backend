package com.example.tenderapp.config;

import com.example.tenderapp.model.Company;
import com.example.tenderapp.model.Role;
import com.example.tenderapp.model.User;
import com.example.tenderapp.repository.CompanyRepository;
import com.example.tenderapp.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds a demo company with one account of each role on first startup (when no users exist yet),
 * so the application is usable immediately. Disable via {@code app.seed.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true", matchIfMissing = true)
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(CompanyRepository companyRepository,
                           UserRepository userRepository,
                           PasswordEncoder passwordEncoder) {
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) {
            return;
        }

        Company company = new Company();
        company.setName("Demo Company");
        company.setRegistrationCode("DEMO-001");
        company.setActive(true);
        company = companyRepository.save(company);

        createUser(company, "owner@demo.com", "owner123", "Demo Owner", Role.OWNER);
        createUser(company, "admin@demo.com", "admin123", "Demo Administrator", Role.ADMINISTRATOR);
        createUser(company, "user@demo.com", "user123", "Demo User", Role.USER);

        log.info("====================================================================");
        log.info("Seeded demo company '{}' with login accounts:", company.getName());
        log.info("  OWNER         -> owner@demo.com / owner123");
        log.info("  ADMINISTRATOR -> admin@demo.com / admin123");
        log.info("  USER          -> user@demo.com  / user123");
        log.info("====================================================================");
    }

    private void createUser(Company company, String email, String password, String fullName, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setFullName(fullName);
        user.setRole(role);
        user.setCompany(company);
        user.setActive(true);
        user.setArchived(false);
        userRepository.save(user);
    }
}
