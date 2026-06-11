package com.example.tenderapp.model;

/**
 * Account roles within a company.
 * <ul>
 *     <li>{@link #OWNER} - top-level account of a company, cannot be removed through the API.</li>
 *     <li>{@link #ADMINISTRATOR} - can manage (add / archive / delete) regular users and administrators.</li>
 *     <li>{@link #USER} - regular user with access to the company data only.</li>
 * </ul>
 */
public enum Role {
    OWNER,
    ADMINISTRATOR,
    USER
}
