package com.example.kladdo.controller;

import com.example.kladdo.support.ApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * What a plan actually enforces. These are the rules a customer pays for, so getting them wrong costs money
 * in one direction or blocks a paying customer in the other.
 *
 * <p>A STARTER company is the fixture throughout because it is the only tier with a small enough seat cap
 * (5) to reach without creating an unreasonable number of accounts.</p>
 */
class PlanEnforcementIntegrationTest extends ApiTestBase {

    /** STARTER allows 5 seats; the owner is the first, so 4 invitations fill it. */
    private static final int STARTER_SEATS = 5;

    @Test
    @DisplayName("the seat cap blocks the account after the last seat is taken")
    void seatCapBlocksTheNextAccount() throws Exception {
        Tenant owner = newBusiness();

        for (int i = 1; i < STARTER_SEATS; i++) {
            assertThat(inviteStatus(owner, "seat-" + i)).as("seat " + (i + 1)).isEqualTo(200);
        }
        assertThat(inviteStatus(owner, "one-too-many")).as("beyond the cap").isEqualTo(403);
    }

    /**
     * Finding N-008. An archived account cannot sign in or do anything until it is unarchived, so it does
     * not occupy a seat. Archiving is the non-destructive way to retire someone; before this fix it was the
     * option that cost you a seat, and deleting the record was the only way to free one.
     */
    @Test
    @DisplayName("archiving frees a seat, and unarchiving takes it back")
    void archivedUsersDoNotConsumeASeat() throws Exception {
        Tenant owner = newBusiness();
        long firstInvitee = 0;

        for (int i = 1; i < STARTER_SEATS; i++) {
            long id = invite(owner, "seat-" + i);
            if (i == 1) {
                firstInvitee = id;
            }
        }
        assertThat(inviteStatus(owner, "blocked")).as("cap reached").isEqualTo(403);

        // Archive one, and the freed seat is immediately usable.
        mvc.perform(authed(put("/api/users/" + firstInvitee + "/archive"), owner));
        assertThat(inviteStatus(owner, "after-archive")).as("seat freed").isEqualTo(200);

        // That seat is now taken again, so unarchiving would exceed the cap - and the next invite is refused.
        assertThat(inviteStatus(owner, "still-full")).as("full again").isEqualTo(403);
    }

    @Test
    @DisplayName("the billing page's usage figure counts the same seats the cap does")
    void reportedUsageMatchesTheEnforcedCap() throws Exception {
        Tenant owner = newBusiness();
        long invitee = invite(owner, "counted");

        assertThat(reportedUsers(owner)).as("owner + one invitee").isEqualTo(2);

        mvc.perform(authed(put("/api/users/" + invitee + "/archive"), owner));
        assertThat(reportedUsers(owner))
                .as("an archived user is not shown as occupying a seat either").isEqualTo(1);
    }

    /**
     * {@code PlanType.WAREHOUSE} is the free tier a 3PL account lands on. A business must never be able to
     * reach it, in any of the three places it could leak in.
     */
    @Test
    @DisplayName("the free WAREHOUSE tier is unreachable for a business")
    void warehouseTierIsNotSelectable() throws Exception {
        Tenant owner = newBusiness();

        JsonNode offered = readJson(mvc.perform(authed(get("/api/subscription"), owner)).andReturn())
                .path("plans");
        for (JsonNode plan : offered) {
            assertThat(plan.path("plan").asText()).as("offered list").isNotEqualTo("WAREHOUSE");
        }

        assertThat(changePlanStatus(owner, "WAREHOUSE")).as("changing onto it").isEqualTo(400);

        // A client-sent plan is not trusted at signup either.
        int status = mvc.perform(post("/api/public/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"companyName":"Injected","fullName":"X","email":"plan-inject@test.local",
                          "password":"testpass123","accountType":"BUSINESS","plan":"WAREHOUSE"}
                         """)).andReturn().getResponse().getStatus();
        assertThat(status).as("signup with an injected plan").isEqualTo(400);
    }

    /**
     * Finding N-009. The cap used to be checked only when creating a user, so downgrading simply landed the
     * company over the new limit — silently, with no warning, and indefinitely.
     */
    @Test
    @DisplayName("a downgrade is refused while the company is over the target plan's seat limit")
    void downgradeIsRefusedWhileOverTheTargetCap() throws Exception {
        Tenant owner = newBusiness();
        assertThat(changePlanStatus(owner, "BUSINESS")).isEqualTo(200);

        // Six seats: fine on BUSINESS (25), one too many for STARTER (5).
        for (int i = 1; i <= 5; i++) {
            assertThat(inviteStatus(owner, "big-" + i)).as("seat " + (i + 1)).isEqualTo(200);
        }
        assertThat(changePlanStatus(owner, "STARTER")).as("over the target cap").isEqualTo(403);

        // Archiving frees a seat (N-008), so the downgrade becomes possible without deleting anyone.
        JsonNode users = readJson(mvc.perform(authed(get("/api/users"), owner)).andReturn());
        long spare = users.get(0).path("id").asLong();
        mvc.perform(authed(put("/api/users/" + spare + "/archive"), owner));

        assertThat(changePlanStatus(owner, "STARTER")).as("now within the cap").isEqualTo(200);
    }

    @Test
    @DisplayName("upgrading is never blocked by the seat check")
    void upgradingIsNeverBlockedBySeats() throws Exception {
        Tenant owner = newBusiness();
        for (int i = 1; i < STARTER_SEATS; i++) {
            invite(owner, "u" + i);
        }
        // Full on STARTER, and moving to a roomier plan must not trip the downgrade guard.
        assertThat(changePlanStatus(owner, "BUSINESS")).isEqualTo(200);
        assertThat(changePlanStatus(owner, "ENTERPRISE")).as("unlimited seats").isEqualTo(200);
    }

    @Test
    @DisplayName("upgrading raises the cap; an unknown plan is rejected")
    void upgradingRaisesTheCap() throws Exception {
        Tenant owner = newBusiness();
        for (int i = 1; i < STARTER_SEATS; i++) {
            invite(owner, "u" + i);
        }
        assertThat(inviteStatus(owner, "capped")).isEqualTo(403);

        assertThat(changePlanStatus(owner, "BUSINESS")).isEqualTo(200);
        assertThat(inviteStatus(owner, "after-upgrade")).as("BUSINESS allows 25").isEqualTo(200);

        assertThat(changePlanStatus(owner, "NOT_A_PLAN")).as("unknown plan").isEqualTo(400);
    }

    // -------------------------------------------------------------------------------------------------

    private int inviteStatus(Tenant owner, String label) throws Exception {
        return mvc.perform(authed(post("/api/users"), owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content(invitePayload(label))).andReturn().getResponse().getStatus();
    }

    private long invite(Tenant owner, String label) throws Exception {
        return readJson(mvc.perform(authed(post("/api/users"), owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content(invitePayload(label))).andReturn()).path("user").path("id").asLong();
    }

    /** Emails must be unique system-wide, so every invitation gets its own suffix. */
    private String invitePayload(String label) {
        return """
               {"email":"%s-%d@test.local","fullName":"Seat %s","role":"USER"}
               """.formatted(label, System.nanoTime(), label);
    }

    private int reportedUsers(Tenant owner) throws Exception {
        JsonNode usage = readJson(mvc.perform(authed(get("/api/subscription"), owner)).andReturn())
                .path("usage");
        for (JsonNode item : usage) {
            if ("USERS".equals(item.path("resource").asText())) {
                return item.path("used").asInt();
            }
        }
        throw new AssertionError("no USERS usage item in the subscription view");
    }

    private int changePlanStatus(Tenant owner, String plan) throws Exception {
        return mvc.perform(authed(put("/api/subscription/plan"), owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"plan":"%s"}
                         """.formatted(plan))).andReturn().getResponse().getStatus();
    }
}
