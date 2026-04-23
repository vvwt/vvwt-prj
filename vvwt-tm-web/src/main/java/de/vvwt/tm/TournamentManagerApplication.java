package de.vvwt.tm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Tournament Manager V1 application entry point.
 *
 * <p>This class is a scaffold placeholder for E02S01. It provides the minimal
 * {@code @SpringBootApplication} entry point required so that E02S02 can add persistence wiring (H2
 * + Flyway) without structural changes.
 *
 * <p>No beans, no data sources, no Flyway configuration — those belong to E02S02.
 *
 * <h2>E23S01 — Parallel-phase bean coexistence (AC-PARALLEL-COEXISTENCE)</h2>
 *
 * <p>During the parallel phase (until E23S05 Cutover-1), both the legacy {@code
 * de.vvwt.tm.domain.photo.*} package and the new {@code de.vvwt.tm.photo.*} module coexist on the
 * classpath. The potential {@code ConflictingBeanDefinitionException} for the shared {@code
 * photoStorageConfig} bean name is resolved by qualifying the new module's config bean as {@code
 * photoModuleStorageConfig} (see {@link de.vvwt.tm.photo.PhotoStorageConfig}). No explicit
 * {@code @ComponentScan} exclusion is needed for photo — the new {@code DefaultPhotoStorageService}
 * and the legacy {@code PhotoStorageServiceImpl} have distinct bean names and implement DIFFERENT
 * Java types ({@code de.vvwt.tm.photo.PhotoStorageService} vs. {@code
 * de.vvwt.tm.domain.photo.PhotoStorageService}), so they do not conflict.
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
 *   <li>{@code de.vvwt.tm.certificate.internal.CertificateTemplateRepository} is registered as
 *       {@code "certificateModuleTemplateRepository"} (vs. legacy {@code
 *       "certificateTemplateRepository"}).
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
 * {@code @SpringBootApplication} provides the default component scan with {@code TypeExcludeFilter}
 * active via its embedded {@code @ComponentScan} — no explicit override is needed.
 *
 * @see <a
 *     href="../../../../../../../../../../../.gaai/project/contexts/artefacts/stories/E02S01.story.md">Story
 *     E02S01</a>
 */
@SpringBootApplication
public class TournamentManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TournamentManagerApplication.class, args);
    }
}
