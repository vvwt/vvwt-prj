/**
 * Public API of the {@code certificate} bounded context (E23S06).
 *
 * <p>This package is the contract surface for the {@code certificate} module. Types declared here
 * are consumable by other Modulith modules; types in {@code de.vvwt.tm.certificate.internal} are
 * implementation details and MUST NOT be accessed by any other module.
 *
 * <h2>Allowed dependencies (DEC-21, DEC-35, DEC-40)</h2>
 *
 * <p>The {@code certificate} context may depend on:
 *
 * <ul>
 *   <li>{@code tournament} — {@link de.vvwt.tm.tournament.TournamentRepository} is consumed by
 *       {@link de.vvwt.tm.certificate.internal.DefaultCertificateTemplateService} for tenant-scoped
 *       tournament validation (DEC-5, DEC-17). Tournament entities are the anchor for certificate
 *       templates (one template per tournament).
 *   <li>{@code photo} — {@link de.vvwt.tm.photo.PhotoStorageService} and related photo types are
 *       consumed by {@code CertificateAssembler} (to be introduced in E23S08) when assembling
 *       certificate content that includes team photos. Declared here per Brief v4 D-5.
 * </ul>
 *
 * <p>The {@code tenant} module is NOT listed — multi-tenancy is transparent to certificate code via
 * DEC-20 DataSource routing. The C2=β decision (Brief v4) removed {@code
 * resolveLocationDisplayName} from the certificate context, eliminating the last direct tenant API
 * dependency.
 *
 * <p>REST controllers that consume {@link de.vvwt.tm.certificate.CertificateTemplateService} reside
 * in {@code de.vvwt.tm.web} per DEC-40 (Primary-Adapter-Isolation); they are NOT in this module.
 *
 * <h2>Parallel-phase coexistence (E23S06, DEC-21)</h2>
 *
 * <p>During the parallel phase (until E23S10 Cutover-2), the legacy {@code
 * de.vvwt.tm.domain.certificate.*} package and {@code de.vvwt.tm.domain.repo
 * .CertificateTemplateRepository} coexist with this module on the classpath. The legacy {@code
 * CertificateTemplateServiceImpl} is excluded from the component scan via an {@code excludeFilters}
 * directive on {@code TournamentManagerApplication} (analog to E22S04/E22S05 precedent for
 * domain.rules bean exclusion). This exclusion is REMOVED at E23S10 Cutover-2 when the legacy
 * classes are deleted.
 *
 * <h2>Flyway migration (DEC-25)</h2>
 *
 * <p>Per-module Flyway migration for the {@code certificate_template} table lives at {@code
 * db/migration/certificate/V1__initial_schema.sql}. The root {@code
 * V15__e12s04_certificate_template.sql} REMAINS on disk during the parallel phase; it is deleted at
 * E23S10 Cutover-2 (DEC-25 Big-Bang-Reset).
 *
 * <p>Authorizing decisions: DEC-21 (Spring Modulith layout), DEC-35 (service interface in public
 * package, implementation in {@code .internal}; {@code Default*Service} naming canon), DEC-40
 * (Primary-Adapter-Isolation — controllers in {@code web}, NOT here).
 *
 * @see de.vvwt.tm.certificate.CertificateTemplateService
 * @since E23S06
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"tournament", "photo"})
package de.vvwt.tm.certificate;
