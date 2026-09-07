package com.example.skladdo.service;

import com.example.skladdo.dto.GoogleRegisterRequest;
import com.example.skladdo.dto.LoginResponse;
import com.example.skladdo.dto.RegisterRequest;
import com.example.skladdo.exception.BadRequestException;
import com.example.skladdo.model.AuthProvider;
import com.example.skladdo.model.Company;
import com.example.skladdo.model.User;
import com.example.skladdo.repository.UserRepository;
import com.example.skladdo.security.ExternalIdentity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GoogleAuthService} - which account a verified Google identity resolves to, and
 * what linking it to that account is allowed to change.
 *
 * <p>Token verification itself is not exercised here: it is Nimbus checking a signature against Google's
 * published keys, so a test of it would be a test of Mockito. What is worth pinning down is everything
 * decided <em>after</em> the token is believed - and in particular the three refusals that are the whole
 * security of the feature: an unverified address, an account already linked to a different Google account,
 * and a retired account.</p>
 */
class GoogleAuthServiceTest {

    private static final String SUBJECT = "108372918273645500001";
    private static final String EMAIL = "jane@acme.test";

    /**
     * Stands in for whatever {@code issueSession} hands back. A real record rather than a mock - a record
     * is final, so Mockito cannot mock one - and its contents are never read here: what this class decides
     * is <em>which</em> account gets a session, not what the session looks like.
     */
    private static final LoginResponse SESSION = new LoginResponse("issued-token", null);

    private final UserRepository users = mock(UserRepository.class);
    private final AuthService authService = mock(AuthService.class);
    private final RegistrationService registrationService = mock(RegistrationService.class);

    private final GoogleAuthService service = new GoogleAuthService(users, authService, registrationService);

    private static ExternalIdentity identity(boolean emailVerified) {
        return new ExternalIdentity(SUBJECT, EMAIL, emailVerified, "Jane Doe");
    }

    private static User account() {
        Company company = new Company();
        company.setId(7L);
        company.setName("Acme");
        company.setActive(true);

        User user = new User();
        user.setId(42L);
        user.setEmail(EMAIL);
        user.setCompany(company);
        return user;
    }

    private static String keyOf(Executable call) {
        return assertThrows(BadRequestException.class, call::run).getMessageKey();
    }

    private interface Executable {
        void run();
    }

    // --- Verification of the address ------------------------------------------------------------

    @Test
    void refusesAnUnverifiedEmailOnLogin() {
        assertEquals("error.auth.google.emailUnverified", keyOf(() -> service.login(identity(false))));
        // Nothing may be looked up on an address the provider has not confirmed, or the refusal above
        // would still have told the caller whether that address has an account.
        verify(users, never()).findByEmailIgnoreCase(anyString());
    }

    @Test
    void refusesAnUnverifiedEmailOnRegister() {
        GoogleRegisterRequest request =
                new GoogleRegisterRequest("token", "Acme", "STARTER", "BUSINESS", null, null);
        assertEquals("error.auth.google.emailUnverified", keyOf(() -> service.register(identity(false), request)));
        verify(registrationService, never()).register(any(), any(), anyString());
    }

    // --- Signing in ------------------------------------------------------------------------------

    @Test
    void signsInAnAccountAlreadyLinkedWithoutTouchingIt() {
        User existing = account();
        existing.setExternalAuthId(SUBJECT);
        when(users.findByExternalAuthId(SUBJECT)).thenReturn(Optional.of(existing));
        when(authService.issueSession(42L)).thenReturn(SESSION);

        service.login(identity(true));

        // Found by subject, so the email is never consulted and nothing is written.
        verify(users, never()).findByEmailIgnoreCase(anyString());
        verify(users, never()).save(any());
        verify(authService).issueSession(42L);
    }

    @Test
    void linksTheIdentityToAMatchingPasswordAccountOnFirstSignIn() {
        User existing = account();
        when(users.findByExternalAuthId(SUBJECT)).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(existing));
        when(users.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
        when(authService.issueSession(42L)).thenReturn(SESSION);

        service.login(identity(true));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertEquals(SUBJECT, saved.getValue().getExternalAuthId());
        // The password still works: linking Google adds a way in, it does not replace one.
        assertEquals(AuthProvider.LOCAL, saved.getValue().getAuthProviderOrLocal());
    }

    @Test
    void activatesAnInvitedAccountThatAcceptsWithGoogle() {
        User invited = account();
        invited.setPasswordSetupPending(true);
        invited.setActive(false);
        when(users.findByExternalAuthId(SUBJECT)).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(invited));
        when(users.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
        when(authService.issueSession(42L)).thenReturn(SESSION);

        service.login(identity(true));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        User result = saved.getValue();
        assertEquals(Boolean.FALSE, result.getPasswordSetupPending());
        assertEquals(Boolean.TRUE, result.getActive());
        // They never chose a password, so the placeholder hash they were created with is all there is.
        assertEquals(AuthProvider.GOOGLE, result.getAuthProviderOrLocal());
    }

    @Test
    void refusesToLinkAnAccountThatAlreadyHasADifferentIdentity() {
        User existing = account();
        existing.setExternalAuthId("a-different-google-account");
        when(users.findByExternalAuthId(SUBJECT)).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(existing));

        // The takeover this prevents: an address given up at the provider and reassigned to someone else,
        // who then signs in and inherits the account it used to belong to.
        assertEquals("error.auth.google.alreadyLinked", keyOf(() -> service.login(identity(true))));
        verify(users, never()).save(any());
    }

    @Test
    void refusesAnAddressWithNoAccount() {
        when(users.findByExternalAuthId(SUBJECT)).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());

        // Signing in never creates a company - there is no name, account type or plan to create one with.
        assertEquals("error.auth.google.noAccount", keyOf(() -> service.login(identity(true))));
    }

    @Test
    void refusesARetiredAccountEvenWhenTheIdentityStillPointsAtIt() {
        User retired = account();
        retired.setExternalAuthId(SUBJECT);
        retired.setDeletedAt(Instant.now());
        when(users.findByExternalAuthId(SUBJECT)).thenReturn(Optional.of(retired));

        assertEquals("error.auth.google.noAccount", keyOf(() -> service.login(identity(true))));
        verify(authService, never()).issueSession(any());
    }

    @Test
    void appliesTheSharedSuspendedCompanyRule() {
        User existing = account();
        existing.setExternalAuthId(SUBJECT);
        when(users.findByExternalAuthId(SUBJECT)).thenReturn(Optional.of(existing));
        when(authService.issueSession(42L)).thenReturn(SESSION);

        service.login(identity(true));

        // Not re-implemented here: the password path and this one must agree on which companies may be
        // entered, so both call the same method.
        verify(authService).assertCompanyNotSuspended(existing);
    }

    // --- Signing up ------------------------------------------------------------------------------

    @Test
    void signsUpThroughTheOrdinaryRegistrationPath() {
        GoogleRegisterRequest request = new GoogleRegisterRequest(
                "token", "  Acme Trading  ", "STARTER", "BUSINESS", "SPRING24", java.util.List.of("TENDERS"));
        when(users.findByExternalAuthId(SUBJECT)).thenReturn(Optional.empty());
        when(registrationService.register(any(), any(), anyString())).thenReturn(SESSION);

        service.register(identity(true), request);

        ArgumentCaptor<RegisterRequest> delegated = ArgumentCaptor.forClass(RegisterRequest.class);
        verify(registrationService).register(delegated.capture(), eq(AuthProvider.GOOGLE), eq(SUBJECT));
        RegisterRequest sent = delegated.getValue();

        // The identity's own claims win over anything the client could have sent.
        assertEquals(EMAIL, sent.email());
        assertEquals("Jane Doe", sent.fullName());
        // Everything Google cannot answer is carried through untouched - the whitespace included, because
        // RegistrationService trims it, and doing it here as well would be a second place to keep in step.
        assertEquals("  Acme Trading  ", sent.companyName());
        assertEquals("STARTER", sent.plan());
        assertEquals("BUSINESS", sent.accountType());
        assertEquals("SPRING24", sent.inviteCode());
        assertEquals(java.util.List.of("TENDERS"), sent.addons());
        // A password is generated only to satisfy the NOT NULL column; nobody can present it.
        assertTrue(sent.password() != null && sent.password().length() >= 8);
    }

    @Test
    void namesTheOwnerAfterTheirAddressWhenGoogleWithholdsTheName() {
        GoogleRegisterRequest request =
                new GoogleRegisterRequest("token", "Acme", "STARTER", "BUSINESS", null, null);
        when(users.findByExternalAuthId(SUBJECT)).thenReturn(Optional.empty());
        when(registrationService.register(any(), any(), anyString())).thenReturn(SESSION);

        // A Workspace account can have its name withheld by policy, and the form no longer asks for one.
        service.register(new ExternalIdentity(SUBJECT, EMAIL, true, null), request);

        ArgumentCaptor<RegisterRequest> delegated = ArgumentCaptor.forClass(RegisterRequest.class);
        verify(registrationService).register(delegated.capture(), any(), anyString());
        assertEquals("jane", delegated.getValue().fullName());
    }

    @Test
    void refusesToSignUpAGoogleAccountThatAlreadyHasALogin() {
        GoogleRegisterRequest request =
                new GoogleRegisterRequest("token", "Acme", "STARTER", "BUSINESS", null, null);
        when(users.findByExternalAuthId(SUBJECT)).thenReturn(Optional.of(account()));

        // Caught here rather than at the unique index, which would surface as a server error whenever the
        // login's address differs from the one Google holds.
        assertEquals("error.register.googleAccountTaken", keyOf(() -> service.register(identity(true), request)));
        verify(registrationService, never()).register(any(), any(), anyString());
    }

    // --- The entity's own defaults ---------------------------------------------------------------

    @Test
    void treatsAnAccountPredatingTheColumnAsLocal() {
        User legacy = account();
        legacy.setAuthProvider(null); // what a row written before the column reads back as

        assertEquals(AuthProvider.LOCAL, legacy.getAuthProviderOrLocal());
        assertFalse(legacy.usesExternalIdentity());
        assertNull(legacy.getExternalAuthId());
    }
}
