package com.example.skladdo.service;

import com.example.skladdo.dto.LoginResponse;
import com.example.skladdo.dto.MicrosoftRegisterRequest;
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
 * Unit tests for {@link MicrosoftAuthService}. See {@code GoogleAuthServiceTest} for the twin sign-up
 * tests, which are unaffected by the difference between the two providers. What is specific to this class
 * is what it deliberately does <em>not</em> do: {@link MicrosoftAuthService#login} never falls back to
 * matching an existing account by email the way {@code GoogleAuthService.login} does, because Microsoft's
 * email claim is not a verified assertion - see {@code MicrosoftIdTokenVerifier}. The tests below exist to
 * catch a regression that "helpfully" reintroduces that fallback.
 */
class MicrosoftAuthServiceTest {

    private static final String SUBJECT = "AAAAAAAAAAAAAAAAAAAAAImZgz1jI4qJEeqO0xh8FQU";
    private static final String EMAIL = "jane@acme.test";

    /** A real record rather than a mock - see {@code GoogleAuthServiceTest} for why. */
    private static final LoginResponse SESSION = new LoginResponse("issued-token", null);

    private final UserRepository users = mock(UserRepository.class);
    private final AuthService authService = mock(AuthService.class);
    private final RegistrationService registrationService = mock(RegistrationService.class);

    private final MicrosoftAuthService service = new MicrosoftAuthService(users, authService, registrationService);

    /** Microsoft's {@code email_verified} is always false - see {@code MicrosoftIdTokenVerifier.verify}. */
    private static ExternalIdentity identity() {
        return new ExternalIdentity(SUBJECT, EMAIL, false, "Jane Doe");
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

    private static String keyOf(Runnable call) {
        return assertThrows(BadRequestException.class, call::run).getMessageKey();
    }

    // --- Signing in: the narrower behaviour ----------------------------------------------------

    @Test
    void signsInAnAccountAlreadyLinkedBySubject() {
        User existing = account();
        existing.setExternalAuthId(SUBJECT);
        when(users.findByExternalAuthId(SUBJECT)).thenReturn(Optional.of(existing));
        when(authService.issueSession(42L)).thenReturn(SESSION);

        service.login(identity());

        verify(authService).issueSession(42L);
    }

    @Test
    void refusesToLinkAMatchingLocalAccountByEmail() {
        // The central guarantee of this class: an account with a matching LOCAL password and no Microsoft
        // link yet must NOT be found and attached to just because the email matches - unlike Google, that
        // match cannot be trusted here. The email lookup must never even be attempted.
        when(users.findByExternalAuthId(SUBJECT)).thenReturn(Optional.empty());

        assertEquals("error.auth.microsoft.noAccount", keyOf(() -> service.login(identity())));

        verify(users, never()).findByEmailIgnoreCase(anyString());
        verify(users, never()).save(any());
        verify(authService, never()).issueSession(any());
    }

    @Test
    void refusesAnAddressWithNoLinkedAccount() {
        when(users.findByExternalAuthId(SUBJECT)).thenReturn(Optional.empty());

        assertEquals("error.auth.microsoft.noAccount", keyOf(() -> service.login(identity())));
    }

    @Test
    void refusesARetiredAccountEvenWhenTheIdentityStillPointsAtIt() {
        User retired = account();
        retired.setExternalAuthId(SUBJECT);
        retired.setDeletedAt(Instant.now());
        when(users.findByExternalAuthId(SUBJECT)).thenReturn(Optional.of(retired));

        assertEquals("error.auth.microsoft.noAccount", keyOf(() -> service.login(identity())));
        verify(authService, never()).issueSession(any());
    }

    @Test
    void appliesTheSharedSuspendedCompanyRule() {
        User existing = account();
        existing.setExternalAuthId(SUBJECT);
        when(users.findByExternalAuthId(SUBJECT)).thenReturn(Optional.of(existing));
        when(authService.issueSession(42L)).thenReturn(SESSION);

        service.login(identity());

        // Not re-implemented here: every sign-in path must agree on which companies may be entered.
        verify(authService).assertCompanyNotSuspended(existing);
    }

    // --- Signing up: identical in shape to Google's ---------------------------------------------

    @Test
    void signsUpThroughTheOrdinaryRegistrationPath() {
        MicrosoftRegisterRequest request = new MicrosoftRegisterRequest(
                "token", "Acme Trading", "STARTER", "BUSINESS", "SPRING24", java.util.List.of("TENDERS"));
        when(users.findByExternalAuthId(SUBJECT)).thenReturn(Optional.empty());
        when(registrationService.register(any(), any(), anyString())).thenReturn(SESSION);

        service.register(identity(), request);

        ArgumentCaptor<RegisterRequest> delegated = ArgumentCaptor.forClass(RegisterRequest.class);
        verify(registrationService).register(delegated.capture(), eq(AuthProvider.MICROSOFT), eq(SUBJECT));
        RegisterRequest sent = delegated.getValue();

        assertEquals(EMAIL, sent.email());
        assertEquals("Jane Doe", sent.fullName());
        assertEquals("Acme Trading", sent.companyName());
        assertTrue(sent.password() != null && sent.password().length() >= 8);
    }

    @Test
    void refusesToSignUpAMicrosoftAccountThatAlreadyHasALogin() {
        MicrosoftRegisterRequest request =
                new MicrosoftRegisterRequest("token", "Acme", "STARTER", "BUSINESS", null, null);
        when(users.findByExternalAuthId(SUBJECT)).thenReturn(Optional.of(account()));

        assertEquals("error.register.microsoftAccountTaken", keyOf(() -> service.register(identity(), request)));
        verify(registrationService, never()).register(any(), any(), anyString());
    }
}
