package com.example.skladdo.controller;

import com.example.skladdo.model.ScheduledEmail;
import com.example.skladdo.model.ScheduledEmailStatus;
import com.example.skladdo.repository.ScheduledEmailRepository;
import com.example.skladdo.security.TenantContext;
import com.example.skladdo.service.ScheduledMaintenanceService;
import com.example.skladdo.support.ApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * End-to-end cover for queueing a send and for the background dispatcher that runs it.
 *
 * <p>The dispatcher is the part worth pinning down, because it runs where nothing else in the app does:
 * a background thread with a tenant bound but nobody authenticated. Two things silently break there and
 * neither shows up in a unit test - the audit row losing its author (it is a {@code @CreatedBy} column
 * with no auditor to report one), and the tenant being bound in the wrong place. Both are asserted here.</p>
 *
 * <p>No SMTP server is reachable from a test, so the individual messages fail to send. That is fine and
 * deliberate: what is under test is the queue mechanics around the send, and a per-recipient SMTP failure
 * is recorded on the {@code SentEmail} row exactly as a real one would be.</p>
 */
class ScheduledEmailIntegrationTest extends ApiTestBase {

    @Autowired
    private ScheduledEmailRepository scheduledEmailRepository;

    @Autowired
    private ScheduledMaintenanceService maintenanceService;

    @Test
    @DisplayName("a queued send fires when its time comes, credited to whoever scheduled it")
    void dueScheduleIsDispatched() throws Exception {
        Tenant owner = newBusiness();
        configureSmtp(owner);
        long clientId = createClient(owner, "Northwind OU");

        JsonNode queued = schedule(owner, clientId, "Autumn price list", Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(queued.path("scheduledId").asLong()).isPositive();
        assertThat(queued.path("sent").asInt()).as("nothing is sent yet").isZero();

        JsonNode list = readJson(mvc.perform(authed(get("/api/scheduled-emails"), owner)).andReturn());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("status").asText()).isEqualTo("PENDING");
        assertThat(list.get(0).path("recipientCount").asInt()).isEqualTo(1);
        assertThat(list.get(0).path("recipientType").asText()).isEqualTo("CLIENT");

        bringDue(owner, queued.path("scheduledId").asLong());
        maintenanceService.dispatchScheduledEmails();

        // The queue row is gone: it fired, and the sent-email rows are the record now.
        assertThat(readJson(mvc.perform(authed(get("/api/scheduled-emails"), owner)).andReturn())).isEmpty();

        JsonNode sent = readJson(mvc.perform(authed(get("/api/sent-emails?size=10"), owner)).andReturn());
        assertThat(sent.path("content")).hasSize(1);
        JsonNode row = sent.path("content").get(0);
        assertThat(row.path("recipientName").asText()).isEqualTo("Northwind OU");
        assertThat(row.path("recipientType").asText()).isEqualTo("CLIENT");
        // The whole point of passing the sender explicitly: a background send still has an author.
        assertThat(row.path("sentById").asLong()).isEqualTo(owner.userId());
    }

    @Test
    @DisplayName("a send whose time has not come is left alone")
    void futureScheduleIsNotDispatched() throws Exception {
        Tenant owner = newBusiness();
        configureSmtp(owner);
        long clientId = createClient(owner, "Later OU");

        schedule(owner, clientId, "Not yet", Instant.now().plus(2, ChronoUnit.HOURS));
        maintenanceService.dispatchScheduledEmails();

        JsonNode list = readJson(mvc.perform(authed(get("/api/scheduled-emails"), owner)).andReturn());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("status").asText()).isEqualTo("PENDING");
        assertThat(readJson(mvc.perform(authed(get("/api/sent-emails?size=10"), owner)).andReturn())
                .path("content")).isEmpty();
    }

    @Test
    @DisplayName("a send the company can no longer make is kept and explained, not silently dropped")
    void undeliverableScheduleIsKeptAsFailed() throws Exception {
        // No SMTP configured, so the whole batch is refused before any message is attempted - the same
        // shape of failure as an add-on lapsing between scheduling and sending.
        Tenant owner = newBusiness();
        long clientId = createClient(owner, "Unreachable OU");

        JsonNode queued = schedule(owner, clientId, "Never leaves", Instant.now().plus(1, ChronoUnit.HOURS));
        bringDue(owner, queued.path("scheduledId").asLong());
        maintenanceService.dispatchScheduledEmails();

        JsonNode list = readJson(mvc.perform(authed(get("/api/scheduled-emails"), owner)).andReturn());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("status").asText()).isEqualTo("FAILED");
        // Translated on read, not left as the raw key the background thread had no locale to resolve.
        assertThat(list.get(0).path("failureReason").asText())
                .isNotEqualTo("error.email.smtpNotConfigured")
                .contains("not set up");
    }

    @Test
    @DisplayName("a queued send can be moved or dropped, but not moved into the past")
    void rescheduleAndCancel() throws Exception {
        Tenant owner = newBusiness();
        configureSmtp(owner);
        long clientId = createClient(owner, "Movable OU");

        long id = schedule(owner, clientId, "Movable", Instant.now().plus(1, ChronoUnit.HOURS))
                .path("scheduledId").asLong();

        Instant later = Instant.now().plus(3, ChronoUnit.HOURS);
        JsonNode moved = readJson(mvc.perform(authed(put("/api/scheduled-emails/" + id), owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scheduledAt\":\"%s\"}".formatted(later))).andReturn());
        assertThat(Instant.parse(moved.path("scheduledAt").asText())).isCloseTo(later, within(2, ChronoUnit.SECONDS));

        assertThat(statusOf(put("/api/scheduled-emails/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scheduledAt\":\"%s\"}".formatted(Instant.now().minusSeconds(60))), owner))
                .as("a time already past").isEqualTo(400);

        assertThat(statusOf(delete("/api/scheduled-emails/" + id), owner)).isEqualTo(200);
        assertThat(readJson(mvc.perform(authed(get("/api/scheduled-emails"), owner)).andReturn())).isEmpty();
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** Queues one email to one client and returns the send response. */
    private JsonNode schedule(Tenant owner, long clientId, String subject, Instant when) throws Exception {
        String request = """
                {"recipientType":"CLIENT","recipientIds":[%d],"subject":"%s",
                 "body":"<p>Hello {{recipient.name}}</p>","scheduledAt":"%s"}
                """.formatted(clientId, subject, when);
        // The multipart builder is not a MockHttpServletRequestBuilder, so it cannot go through authed().
        return readJson(mvc.perform(multipart("/api/emails/send")
                .file(new MockMultipartFile("request", "request", MediaType.APPLICATION_JSON_VALUE,
                        request.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .header("Authorization", "Bearer " + owner.token())).andReturn());
    }

    /**
     * Rewinds a queued send so the next dispatcher pass picks it up. Reaches past the API on purpose -
     * scheduling into the past is refused, which is the behaviour the fourth test pins down.
     */
    private void bringDue(Tenant owner, long scheduledId) {
        TenantContext.callAs(owner.companyId(), () -> {
            ScheduledEmail row = scheduledEmailRepository.findById(scheduledId).orElseThrow();
            row.setScheduledAt(Instant.now().minusSeconds(60));
            row.setStatus(ScheduledEmailStatus.PENDING);
            return scheduledEmailRepository.save(row);
        });
    }

    /** Enough SMTP settings for the send to be attempted. Nothing listens there - see the class note. */
    private void configureSmtp(Tenant owner) throws Exception {
        ObjectNode settings = (ObjectNode) readJson(
                mvc.perform(authed(get("/api/settings"), owner)).andReturn());
        settings.put("smtpHost", "localhost");
        settings.put("smtpPort", 1);
        settings.put("smtpUsername", "it@test.local");
        settings.put("smtpFromAddress", "it@test.local");
        mvc.perform(authed(put("/api/settings"), owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(settings)));
    }

    private long createClient(Tenant owner, String name) throws Exception {
        JsonNode created = readJson(mvc.perform(authed(post("/api/clients"), owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"name":"%s","email":"buyer@%s.example"}
                         """.formatted(name, name.split(" ")[0].toLowerCase(java.util.Locale.ROOT)))).andReturn());
        return created.path("id").asLong();
    }
}
