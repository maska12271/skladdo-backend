package com.example.skladdo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

/**
 * Resolves the request locale from the {@code Accept-Language} header so error messages can be
 * returned in the caller's language. The frontend sends the user's chosen language (en/et/ru) on
 * every request; anything else falls back to English (the base {@code messages.properties} bundle).
 */
@Configuration
public class I18nConfig {

    private static final List<Locale> SUPPORTED = List.of(
            Locale.ENGLISH, Locale.of("et"), Locale.of("ru"));

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setSupportedLocales(SUPPORTED);
        resolver.setDefaultLocale(Locale.ENGLISH);
        return resolver;
    }
}
