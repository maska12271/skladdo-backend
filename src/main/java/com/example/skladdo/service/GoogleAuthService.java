package com.example.skladdo.service;

import com.example.skladdo.dto.GoogleRegisterRequest;
import com.example.skladdo.dto.LoginResponse;
import com.example.skladdo.dto.RegisterRequest;
import com.example.skladdo.exception.BadRequestException;
import com.example.skladdo.model.AuthProvider;
import com.example.skladdo.model.User;
import com.example.skladdo.repository.UserRepository;
import com.example.skladdo.security.ExternalIdentity;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Turns a verified Google identity into a Skladdo session.
 *
 * <p>Google only replaces the step that establishes <em>who this is</em>. Once that is settled the session
 * is minted by {@link AuthService#issueSession}, exactly as a password login, a signup and a company switch
 * all do - so nothing downstream (tenancy, the company switcher, the admin panel's authority check) knows
 * or cares that the session began at Google.</p>
 *
 * <p>Deliberately not {@code @Transactional}. {@link RegistrationService} documents why it must not run
 * inside one - the tenant discriminator is fixed when the Hibernate session opens, so the new company has
 * to be bound in a session of its own - and wrapping the call here would reintroduce exactly that. The
 * writes this class does are single {@code save} calls, each transactional in its own right.</p>
 */
@Service
public class GoogleAuthService {

    private final UserRepository userRepository;
    private final AuthService authService;
    private final RegistrationService registrationService;

    public GoogleAuthService(UserRepository userRepository,
                             AuthService authService,
                             RegistrationService registrationService) {
        this.userRepository = userRepository;
        this.authService = authService;
        this.registrationService = registrationService;
    }

    /**
     * Signs in the account this Google identity belongs to, linking the two on the first sign-in.
     *
     * <p>Signing in never creates a company. A brand-new visitor is turned away with
     * {@code error.auth.google.noAccount} and pointed at signup instead, because a company needs a name, an
     * account type and a plan - none of which a Google token can answer, and all of which someone would
     * then be billed for without having been asked.</p>
     */
    public LoginResponse login(ExternalIdentity identity) {
        requireVerifiedEmail(identity);

        Optional<User> linked = userRepository.findByExternalAuthId(identity.subject());
        User account = linked.isPresent() ? linked.get() : linkByEmail(identity);

        // A retired account reads as no account at all, the same as it does on the password path: its row
        // survives only so old records can still name the person.
        if (account.isDeleted()) {
            throw new BadRequestException("error.auth.google.noAccount");
        }
        authService.assertCompanyNotSuspended(account);

        return authService.issueSession(account.getId());
    }

    /**
     * Provisions a new company whose owner signs in with Google rather than a password.
     *
     * <p>Everything except the credential is the ordinary signup - same invite-link terms, same plan and
     * add-on validation, same refusal to name the platform's own account type - because it runs the
     * ordinary signup.</p>
     */
    public LoginResponse register(ExternalIdentity identity, GoogleRegisterRequest request) {
        requireVerifiedEmail(identity);

        // Checked before anything is created: the email collision below is caught by RegistrationService,
        // but a Google account already linked to a login under a *different* address would otherwise get
        // as far as the unique index on external_auth_id and fail as a server error.
        if (userRepository.findByExternalAuthId(identity.subject()).isPresent()) {
            throw new BadRequestException("error.register.googleAccountTaken");
        }

        RegisterRequest delegate = new RegisterRequest(
                request.companyName(),
                identity.displayName(),
                identity.email(),
                // Satisfies the NOT NULL password column with something nobody can present - the same trick
                // an invited account uses. AuthProvider.GOOGLE below is what makes it unreachable rather
                // than merely unguessable.
                UUID.randomUUID().toString(),
                request.plan(),
                request.accountType(),
                request.inviteCode(),
                request.addons());

        return registrationService.register(delegate, AuthProvider.GOOGLE, identity.subject());
    }

    /**
     * The account for this identity's email address, with the identity now attached to it.
     *
     * <p>This is the only place an external identity is matched on an email rather than on the provider's
     * permanent id, and it happens once per account - every later sign-in finds the link directly. That
     * matters: an address can be given up and reassigned, so linking on it a second time would let whoever
     * holds the address next inherit the account.</p>
     */
    private User linkByEmail(ExternalIdentity identity) {
        User account = userRepository.findByEmailIgnoreCase(identity.email())
                .filter(user -> !user.isDeleted())
                .orElseThrow(() -> new BadRequestException("error.auth.google.noAccount"));

        // Reached only when the lookup by subject found nothing, so an identity already on this row is a
        // different one. Refused rather than overwritten: that is the reassigned-address case above.
        if (account.getExternalAuthId() != null) {
            throw new BadRequestException("error.auth.google.alreadyLinked");
        }

        account.setExternalAuthId(identity.subject());

        // An invited colleague who signs in with Google has done everything the emailed setup link asks
        // for, and rather more: the link only proves they can open their mailbox, while Google has just
        // confirmed the address afresh. Turning them away to go and find that email would be theatre.
        if (Boolean.TRUE.equals(account.getPasswordSetupPending())) {
            account.setPasswordSetupPending(false);
            account.setActive(true);
            // They never chose a password, so the placeholder hash they were created with is all there is.
            account.setAuthProvider(AuthProvider.GOOGLE);
        }
        return userRepository.save(account);
    }

    /**
     * An unverified address is not evidence of anything: a provider account can carry whatever address its
     * owner typed, so honouring one would let anybody claim any Skladdo account by putting its email on a
     * Google account of their own.
     */
    private static void requireVerifiedEmail(ExternalIdentity identity) {
        if (!identity.emailVerified()) {
            throw new BadRequestException("error.auth.google.emailUnverified");
        }
    }

}
