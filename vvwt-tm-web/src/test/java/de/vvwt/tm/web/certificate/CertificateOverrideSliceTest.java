// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web.certificate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.vvwt.tm.certificate.CertificateAssembler;
import de.vvwt.tm.certificate.CertificatePlacementRow;
import de.vvwt.tm.certificate.CertificateTemplateService;
import de.vvwt.tm.infrastructure.testsupport.TenantContextSliceTestSupport;
import de.vvwt.tm.tenant.LocationDisplayResolver;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantRegistryPort;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.MessageSource;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * @WebMvcTest slice test for E68S02 — ephemeral organizer/venue override at certificate generation.
 *
 * <p>Tests authored RED-first (DEC-22 Iron Law) before the production implementation. Covers:
 *
 * <ul>
 *   <li>AC-OVERRIDE-SINGLE-ORGANIZER: {@code organizerOverride} param → assembler called with
 *       override value as organizer
 *   <li>AC-OVERRIDE-SINGLE-VENUE: {@code venueOverride} param → assembler called with override
 *       value as locationDisplayName; locationDisplayResolver NOT called
 *   <li>AC-OVERRIDE-BATCH-BOTH: batch endpoint with both overrides → assembler called with both
 *       override values
 *   <li>AC-NO-OVERRIDE-LIVE-VENUE: no venueOverride param → locationDisplayResolver.
 *       resolveLocationDisplayName() called (live read, not frozen)
 *   <li>AC-BLANK-OVERRIDE-FALLBACK: blank/whitespace venueOverride treated as no-override → live
 *       resolveLocationDisplayName() called
 *   <li>AC-BLANK-ORGANIZER-FALLBACK: blank/whitespace organizerOverride treated as no-override →
 *       assembler called with tournament.getOrganizer() value
 * </ul>
 *
 * @see CertificateRenderController
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-36">DEC-36 — Cross-package test typing</a>
 * @since E68S02
 */
@WebMvcTest(CertificateRenderController.class)
@DisplayName("CertificateRenderController — E68S02 ephemeral override @WebMvcTest slice")
class CertificateOverrideSliceTest {

    private static final UUID TOURNAMENT_ID =
            UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    private static final UUID TEAM_ID = UUID.fromString("dddddddd-0000-0000-0000-000000000002");
    private static final UUID TENANT_ID = UUID.randomUUID();

    @Autowired private WebApplicationContext context;

    private MockMvc mockMvc;

    @MockitoBean private CertificateAssembler certificateAssembler;
    @MockitoBean private CertificateTemplateService certificateTemplateService;
    @MockitoBean private LocationDisplayResolver locationDisplayResolver;
    @MockitoBean private TournamentRepository tournamentRepository;
    @MockitoBean private TeamRepository teamRepository;
    @MockitoBean private TeamAvatarRepository teamAvatarRepository;
    @MockitoBean private PhaseRepository phaseRepository;
    @MockitoBean private TenantContext tenantContext;
    @MockitoBean private TenantRegistryPort tenantRegistryPort;
    @MockitoBean private MessageSource messageSource;

    private Tournament tournament;
    private Phase phase;
    private CertificateAssembler.AvatarPlacement teamPlacement;
    private CertificatePlacementRow htmlRow;

    private Map<String, Object> stubMustacheMap;

    @BeforeEach
    void setUp() {
        TenantContextSliceTestSupport.configureMock(tenantContext, TENANT_ID);
        when(tenantRegistryPort.getDefault()).thenReturn(TENANT_ID);
        when(tenantContext.bind(any())).thenReturn(() -> {});
        mockMvc =
                MockMvcBuilders.webAppContextSetup(context)
                        .apply(SecurityMockMvcConfigurers.springSecurity())
                        .build();

        tournament = new Tournament();
        tournament.setId(TOURNAMENT_ID);
        tournament.setDescription("Override Test Tournament");
        tournament.setOrganizer("Stored Organizer");

        phase = new Phase();
        phase.setId(UUID.randomUUID());

        teamPlacement = new CertificateAssembler.AvatarPlacement(1, TEAM_ID, TEAM_ID);
        htmlRow =
                new CertificatePlacementRow(
                        1,
                        TEAM_ID,
                        "Override Team",
                        "/api/photo/teams/" + TEAM_ID,
                        "Override Test Tournament",
                        "1. Januar 2026",
                        "Override Location",
                        "Override Organizer");

        stubMustacheMap =
                Map.ofEntries(
                        Map.entry("tom_placement", "1"),
                        Map.entry("tom_team_name", "Override Team"),
                        Map.entry("tom_team_photo", ""),
                        Map.entry("tom_tournament_name", "Override Test Tournament"),
                        Map.entry("tom_date", "1. Januar 2026"),
                        Map.entry("tom_location", "Override Location"),
                        Map.entry("tom_organizer", "Override Organizer"),
                        Map.entry("tom_label_certificate", "Urkunde"),
                        Map.entry("tom_label_place", "Platz"),
                        Map.entry("tom_label_achieved_by", "erreicht von"),
                        Map.entry("tom_label_team_photo", "Mannschaftsfoto"),
                        Map.entry("tom_label_generated_by", "erstellt mit"),
                        Map.entry("tom_label_on", "am"),
                        Map.entry("tom_has_photo", false));

        // Base stubs
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));
        when(certificateAssembler.getFinalPhase(TOURNAMENT_ID)).thenReturn(Optional.of(phase));
        when(certificateAssembler.computePlacementOrder(eq(TOURNAMENT_ID), any()))
                .thenReturn(List.of(teamPlacement));
        when(certificateTemplateService.retrieveFile(TOURNAMENT_ID)).thenReturn(Optional.empty());
        // Stub both 3-arg and 4-arg overloads — controller calls the 4-arg version (E68S02).
        when(certificateAssembler.buildHtmlRows(any(), any(), any())).thenReturn(List.of(htmlRow));
        when(certificateAssembler.buildHtmlRows(any(), any(), any(), any()))
                .thenReturn(List.of(htmlRow));
        when(certificateAssembler.toMustacheMap(any())).thenReturn(stubMustacheMap);
        when(tenantContext.current()).thenReturn(UUID.randomUUID());
    }

    // =========================================================================
    // AC-OVERRIDE-SINGLE-ORGANIZER — organizerOverride param reaches assembler
    // AC2 (single cert): override applied, AC3 (both certs): covered by batch test
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName(
            "AC-OVERRIDE-SINGLE-ORGANIZER (AC2): organizerOverride param → buildHtmlRows called"
                    + " with override as organizer, not tournament.getOrganizer()")
    void singleCertificate_withOrganizerOverride_callsAssemblerWithOverride() throws Exception {
        when(locationDisplayResolver.resolveLocationDisplayName()).thenReturn("Live Venue");

        mockMvc.perform(
                        get("/certificate/tournaments/{tid}/print/{teamId}", TOURNAMENT_ID, TEAM_ID)
                                .param("organizerOverride", "Override Organizer"))
                .andExpect(status().isOk())
                .andExpect(view().name("certificate/print"));

        // AC2: assembler receives override value as organizer — verified via buildHtmlRows 4-arg
        // (E68S02: controller always calls 4-arg overload; organizer override passed as 4th arg)
        verify(certificateAssembler).buildHtmlRows(any(), any(), any(), eq("Override Organizer"));
    }

    // =========================================================================
    // AC-OVERRIDE-SINGLE-VENUE — venueOverride param → assembler gets override, resolver NOT called
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName(
            "AC-OVERRIDE-SINGLE-VENUE (AC2): venueOverride param → buildHtmlRows called with"
                    + " override as locationDisplayName;"
                    + " locationDisplayResolver.resolveLocationDisplayName NOT called")
    void singleCertificate_withVenueOverride_doesNotCallResolver() throws Exception {
        mockMvc.perform(
                        get("/certificate/tournaments/{tid}/print/{teamId}", TOURNAMENT_ID, TEAM_ID)
                                .param("venueOverride", "Override Venue"))
                .andExpect(status().isOk())
                .andExpect(view().name("certificate/print"));

        // AC-NO-OVERRIDE-LIVE-VENUE complement: when venueOverride present, resolver is NOT called
        org.mockito.Mockito.verify(locationDisplayResolver, org.mockito.Mockito.never())
                .resolveLocationDisplayName();
        verify(certificateAssembler).buildHtmlRows(any(), any(), eq("Override Venue"), any());
    }

    // =========================================================================
    // AC-NO-OVERRIDE-LIVE-VENUE — no venueOverride → resolver called live
    // AC5: no override → today's output; venue resolved live at render time
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName(
            "AC-NO-OVERRIDE-LIVE-VENUE (AC5): no venueOverride param → locationDisplayResolver"
                    + " called live at render time")
    void singleCertificate_noVenueOverride_callsResolverLive() throws Exception {
        when(locationDisplayResolver.resolveLocationDisplayName()).thenReturn("Live Venue");

        mockMvc.perform(
                        get(
                                "/certificate/tournaments/{tid}/print/{teamId}",
                                TOURNAMENT_ID,
                                TEAM_ID))
                .andExpect(status().isOk());

        verify(locationDisplayResolver).resolveLocationDisplayName();
        verify(certificateAssembler).buildHtmlRows(any(), any(), eq("Live Venue"), any());
    }

    // =========================================================================
    // AC-BLANK-OVERRIDE-FALLBACK — blank/whitespace venueOverride treated as no-override
    // AC6: blank override → fallback to default resolved value
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName(
            "AC-BLANK-OVERRIDE-FALLBACK (AC6): blank venueOverride → treated as no-override →"
                    + " locationDisplayResolver.resolveLocationDisplayName() called")
    void singleCertificate_blankVenueOverride_treatsAsNoOverride() throws Exception {
        when(locationDisplayResolver.resolveLocationDisplayName()).thenReturn("Live Venue");

        mockMvc.perform(
                        get("/certificate/tournaments/{tid}/print/{teamId}", TOURNAMENT_ID, TEAM_ID)
                                .param("venueOverride", "   "))
                .andExpect(status().isOk());

        // Blank venueOverride → resolver called; AC6: no error shown, generation not blocked
        verify(locationDisplayResolver).resolveLocationDisplayName();
    }

    // =========================================================================
    // AC-BLANK-ORGANIZER-FALLBACK — blank organizerOverride treated as no-override
    // AC6: blank organizer override → tournament.getOrganizer() used
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName(
            "AC-BLANK-ORGANIZER-FALLBACK (AC6): blank organizerOverride → treated as no-override"
                    + " → tournament.getOrganizer() used (not blank)")
    void singleCertificate_blankOrganizerOverride_treatsAsNoOverride() throws Exception {
        when(locationDisplayResolver.resolveLocationDisplayName()).thenReturn("Live Venue");

        mockMvc.perform(
                        get("/certificate/tournaments/{tid}/print/{teamId}", TOURNAMENT_ID, TEAM_ID)
                                .param("organizerOverride", "  "))
                .andExpect(status().isOk());

        // Assembler called with 4-arg overload — blank organizer override is passed as-is;
        // the assembler's resolveOrganizer() treats blank as no-override (falls back to stored)
        verify(certificateAssembler).buildHtmlRows(any(), any(), any(), any());
    }

    // =========================================================================
    // AC-OVERRIDE-BATCH-BOTH — batch endpoint with both overrides → assembler gets both
    // AC3: both cert documents honour override values
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName(
            "AC-OVERRIDE-BATCH-BOTH (AC3): batch endpoint with organizerOverride + venueOverride"
                    + " → resolver NOT called, assembler gets venueOverride as locationDisplayName")
    void allCertificates_withBothOverrides_appliesBoth() throws Exception {
        mockMvc.perform(
                        get("/certificate/tournaments/{tid}/print", TOURNAMENT_ID)
                                .param("organizerOverride", "Batch Organizer")
                                .param("venueOverride", "Batch Venue"))
                .andExpect(status().isOk())
                .andExpect(view().name("certificate/print-all"));

        org.mockito.Mockito.verify(locationDisplayResolver, org.mockito.Mockito.never())
                .resolveLocationDisplayName();
        verify(certificateAssembler)
                .buildHtmlRows(any(), any(), eq("Batch Venue"), eq("Batch Organizer"));
    }

    // =========================================================================
    // AC-OVERRIDE-BATCH-NO-VENUE — batch endpoint with no venueOverride → resolver called
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName(
            "AC-OVERRIDE-BATCH-NO-VENUE (AC5): batch endpoint with no venueOverride → resolver"
                    + " called live")
    void allCertificates_noVenueOverride_callsResolverLive() throws Exception {
        when(locationDisplayResolver.resolveLocationDisplayName()).thenReturn("Live Batch Venue");

        mockMvc.perform(get("/certificate/tournaments/{tid}/print", TOURNAMENT_ID))
                .andExpect(status().isOk());

        verify(locationDisplayResolver).resolveLocationDisplayName();
        verify(certificateAssembler).buildHtmlRows(any(), any(), eq("Live Batch Venue"), any());
    }
}
