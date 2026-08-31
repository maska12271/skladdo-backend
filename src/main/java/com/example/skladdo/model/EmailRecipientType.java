package com.example.skladdo.model;

/**
 * Which side of the address book an email went to. A send is always one type at a time: the compose
 * form is opened from the clients list or the manufacturers list, and a {@link PartnerContact} belongs
 * to one partner, so a mixed batch would have no answer to "which contact".
 */
public enum EmailRecipientType {
    MANUFACTURER,
    CLIENT
}
