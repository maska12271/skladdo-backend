package com.example.skladdo.service;

import com.example.skladdo.dto.LoginResponse;
import com.example.skladdo.dto.MicrosoftRegisterRequest;
import com.example.skladdo.dto.RegisterRequest;
import com.example.skladdo.exception.BadRequestException;
import com.example.skladdo.model.AuthProvider;
import com.example.skladdo.model.User;
import com.example.skladdo.repository.UserRepository;
import com.example.skladdo.security.ExternalIdentity;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Turns a verified Microsoft identity into a Skladdo session. The counterpart to {@link GoogleAuthService}
 * - same shape, same downstream ({@link AuthService#issueSession}, so nothing past verification knows or
 * cares which provider a session began at) - but deliberately narrower in one place.
 *
 * <p><b>This class never links a Microsoft identity to an existing account by matching email.</b>
 * {@link GoogleAuthService#login} does that (see its {@code linkByEmail}), which is safe there because
 * Google's {@code email_verified} claim is a real assertion. Microsoft's is not - see
 * {@link com.example.skladdo.security.MicrosoftIdTokenVerifier} for why - so a login attempt that does not
 * already carry a link finds nothing to sign into, rather than attaching itself to somebody else's account
 * on the strength of a claim Microsoft itself says not to trust for this. Signing up (which never touches
 * an existing account) and accepting a colleague's invitation (which is gated by the invitation token, not
 * by email matching) are both unaffected by this and work exactly as they do for Google.</p>
 */
@Service
public class MicrosoftAuthService {

    private final UserRepository userRepository;
    private final AuthService authService;
    private final RegistrationService registrationService;

    public MicrosoftAuthService(UserRepository userRepository,
                                AuthService authService,
                                RegistrationService registrationService) {
        this.userRepository = userRepository;
        this.authService = authService;
        this.registrationService = registrationService;
    }

    /**
     * Signs in the account this Microsoft identity is already linked to.
     *
     * <p>Never creates a company, for the same reason {@link GoogleAuthService#login} does not, and never
     * links to an existing account by email, for the reason in this class's own documentation - so an
     * identity with no link on file is refused outright rather than matched against anything.</p>
     */
    public LoginResponse login(ExternalIdentity identity) {
        User account = userRepository.findByExternalAuthId(identity.subject())
                .orElseThrow(() -> new BadRequestException("error.auth.microsoft.noAccount"));

        // A retired account reads as no account at all, the same as every other sign-in path: its row
        // survives only so old records can still name the person.
        if (account.isDeleted()) {
            throw new BadRequestException("error.auth.microsoft.noAccount");
        }
        authService.assertCompanyNotSuspended(account);

        return authService.issueSession(account.getId());
    }

    /**
     * Provisions a new company whose owner signs in with Microsoft rather than a password. Identical in
     * shape to {@link GoogleAuthService#register} - everything the invite link, the plan and the add-on
     * validation decide runs through the same ordinary signup either way.
     */
    public LoginResponse register(ExternalIdentity identity, MicrosoftRegisterRequest request) {
        // Checked before anything is created: the email collision below is caught by RegistrationService,
        // but a Microsoft account already linked to a login under a *different* address would otherwise get
        // as far as the unique index on external_auth_id and fail as a server error.
        if (userRepository.findByExternalAuthId(identity.subject()).isPresent()) {
            throw new BadRequestException("error.register.microsoftAccountTaken");
        }

        RegisterRequest delegate = new RegisterRequest(
                request.companyName(),
                identity.displayName(),
                identity.email(),
                // Satisfies the NOT NULL password column with something nobody can present - the same trick
                // an invited account uses. AuthProvider.MICROSOFT below is what makes it unreachable rather
                // than merely unguessable.
                UUID.randomUUID().toString(),
                request.plan(),
                request.accountType(),
                request.inviteCode(),
                request.addons());

        return registrationService.register(delegate, AuthProvider.MICROSOFT, identity.subject());
    }
}
