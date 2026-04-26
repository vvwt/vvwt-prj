package de.vvwt.tm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Tournament Manager V1 application entry point.
 *
 * <p>This class is a scaffold placeholder for E02S01. It provides the minimal
 * {@code @SpringBootApplication} entry point required so that E02S02 can add persistence wiring (H2
 * + Flyway) without structural changes.
 *
 * <p>No beans, no data sources, no Flyway configuration — those belong to E02S02.
 *
 * <h2>E23S01 / E36S01 — Photo parallel-phase resolved; E36 Q-1a rebuild complete</h2>
 *
 * <p>As of E23S05 Cutover-1, the legacy photo domain and controller packages were deleted. The
 * canonical {@code de.vvwt.tm.photo.*} and {@code de.vvwt.tm.web.photo.*} modules were rebuilt
 * under Q-1a TDD at E36S01 + E36S02 (Option γ, same FQN). No photo-related {@code @ComponentScan}
 * exclusion is needed.
 *
 * <h2>E23S06 — Certificate parallel-phase bean coexistence</h2>
 *
 * <p>During the parallel phase (until E23S10 Cutover-2), the legacy certificate classes coexist
 * with the new {@code de.vvwt.tm.certificate.*} module. Bean-name conflicts are resolved by
 * qualifying the new module's beans:
 *
 * <ul>
 *   <li>{@code de.vvwt.tm.certificate.CertificateTemplateStorageConfig} is registered as {@code
 *       "certificateModuleStorageConfig"} (vs. legacy {@code "certificateTemplateStorageConfig"}).
 *   <li>{@code de.vvwt.tm.certificate.internal.DefaultCertificateTemplateRepository} is registered
 *       as {@code "defaultCertificateTemplateRepository"} (vs. legacy {@code
 *       "certificateTemplateRepository"}). Renamed from concrete class {@code
 *       CertificateTemplateRepository} to {@code DefaultCertificateTemplateRepository} + interface
 *       extracted at E23S07 (DEC-35 naming canon).
 * </ul>
 *
 * <p>The new {@code DefaultCertificateTemplateService} (bean name {@code
 * "defaultCertificateTemplateService"}) and the legacy {@code CertificateTemplateServiceImpl} (bean
 * name {@code "certificateTemplateServiceImpl"}) have different names and implement DIFFERENT Java
 * interfaces, so they do not conflict. No {@code @ComponentScan} exclusion is needed for E23S06 —
 * all conflicts are resolved by explicit bean-name qualification. These qualifications are removed
 * at E23S10 Cutover-2 when the legacy classes are deleted.
 *
 * <h2>Post-E22S11 — @ComponentScan exclusions removed</h2>
 *
 * <p>The transitional {@code @ComponentScan(excludeFilters = ...)} annotation introduced in
 * E22S04/E22S05 (5 domain.rules rule beans) and E22S10 (legacy
 * infrastructure.score.ScoreController) is removed here. With the E22S11 atomic cutover, all legacy
 * {@code domain.rules.*} and {@code infrastructure.score.*} classes are deleted from the classpath.
 * The duplicate-bean and ambiguous-mapping hazards that required the exclusions no longer exist.
 *
 * <h2>E23S05 Cutover-1 — @ComponentScan(excludeFilters) for photo parallel-phase removed</h2>
 *
 * <p>The REGEX excludeFilter introduced in E23S04 to prevent URL-mapping collision during the
 * parallel phase is removed here. The legacy controller class was deleted at Cutover-1. The {@code
 * TypeExcludeFilter} exclusion is preserved to protect against duplicate {@code @TestConfiguration}
 * inner-class bean definitions during {@code @SpringBootTest} context loading (E22S04/E22S05
 * precedent).
 *
 * <h2>E23S10 Cutover-2 — Certificate @ComponentScan exclusion removed</h2>
 *
 * <p>The REGEX excludeFilter for the legacy certificate infrastructure web package introduced in
 * E23S09 is removed here. The legacy controller class and its DTOs have been deleted at Cutover-2.
 * The {@code TypeExcludeFilter} exclusion is preserved to protect against duplicate
 * {@code @TestConfiguration} inner-class bean definitions during {@code @SpringBootTest} context
 * loading (E22S04/E22S05 precedent).
 *
 * @see de.vvwt.tm.web.photo.TeamPhotoController
 * @see de.vvwt.tm.web.certificate.CertificateTemplateController
 * @see <a
 *     href="../../../../../../../../../../../.gaai/project/contexts/artefacts/stories/E02S01.story.md">Story
 *     E02S01</a>
 */
@SpringBootApplication
@ComponentScan(
        excludeFilters = {
            @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class)
        })
public class TournamentManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TournamentManagerApplication.class, args);
    }
}
