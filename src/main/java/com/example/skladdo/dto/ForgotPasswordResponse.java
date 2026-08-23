package com.example.skladdo.dto;

/**
 * Outcome of a public "forgot password" request. Reports only whether the email actually went out, so the
 * page can stop telling people to check an inbox that will never receive anything (a company's SMTP is
 * unset until they configure it, which is the state every new tenant starts in).
 *
 * <p>Deliberately does <b>not</b> carry the reset link, unlike the admin-facing
 * {@link SetupLinkResponse}. This endpoint is unauthenticated: returning the link would let anyone take
 * over any account by asking for it.</p>
 */
public record ForgotPasswordResponse(boolean emailSent) {
}
