/**
 * Public API of the {@code phaselifecycle} bounded context.
 *
 * <p>This package is the Saga-Orchestrator mediator for the SlotOpt + MatchGen background-job
 * pipeline introduced by DEC-64 (authored 2026-05-11). It replaces the events-only pattern of
 * DEC-55 D-3 with an imperative orchestrator backed by a DB-durable per-tournament job queue.
 *
 * <h2>Module topology (DEC-64 D-2 + DEC-21)</h2>
 *
 * <p>The {@code phaselifecycle} module depends on {@code tournament}, {@code slotopt}, and {@code
 * tenant}. This third-party mediator topology resolves the original {@code tournament} ↔ {@code
 * slotopt} cycle constraint by structure, not by event indirection: {@code phaselifecycle} is the
 * only module that imports from both; {@code tournament} and {@code slotopt} retain NO direct edge
 * to each other.
 *
 * <h2>Public interface contracts (DEC-64 D-14 + DEC-58)</h2>
 *
 * <p>Per DEC-58 (universal interface mandate) and DEC-35 (naming canon), every self-created Spring
 * bean in this module has a public interface declared here in the module-root package and a {@code
 * Default*} implementation in {@link de.vvwt.tm.phaselifecycle.internal}. The six contracts
 * declared across E55S01–E55S06 are:
 *
 * <ol>
 *   <li>{@link de.vvwt.tm.phaselifecycle.PhaseLifecycleOrchestrator} — drain-step entry point;
 *       implementation: {@link
 *       de.vvwt.tm.phaselifecycle.internal.DefaultPhaseLifecycleOrchestrator} (E55S04)
 *   <li>{@link de.vvwt.tm.phaselifecycle.PhaseLifecycleJobRepository} — DAO for {@code
 *       phase_lifecycle_job} table; implementation: {@link
 *       de.vvwt.tm.phaselifecycle.internal.DefaultPhaseLifecycleJobRepository} (E55S02)
 *   <li>{@link de.vvwt.tm.phaselifecycle.WorkerRegistry} — per-tournament {@code
 *       ConcurrentHashMap<UUID, ExecutorService>}; implementation: {@link
 *       de.vvwt.tm.phaselifecycle.internal.DefaultWorkerRegistry} (E55S03)
 *   <li>{@link de.vvwt.tm.phaselifecycle.JobDrainService} — tick logic: claim + dispatch;
 *       implementation: {@link de.vvwt.tm.phaselifecycle.internal.DefaultJobDrainService} (E55S04)
 *   <li>{@link de.vvwt.tm.phaselifecycle.CancelFlagRegistry} — in-memory cancel flag mirror;
 *       implementation: {@link de.vvwt.tm.phaselifecycle.internal.DefaultCancelFlagRegistry}
 *       (E55S05)
 *   <li>{@link de.vvwt.tm.phaselifecycle.DraftApplicationOrchestrator} — apply-and-orchestrate
 *       entry-point: wraps {@link de.vvwt.tm.tournament.DraftService#apply} + enqueues job rows +
 *       triggers drain (E55S06, Option C, DEC-64 D-11); implementation: {@link
 *       de.vvwt.tm.phaselifecycle.internal.DefaultDraftApplicationOrchestrator}
 * </ol>
 *
 * <p>E55S01 declared contracts 1–5 only. E55S06 adds contract 6 (DraftApplicationOrchestrator).
 *
 * <h2>Allowed dependencies (DEC-21, DEC-64 D-2)</h2>
 *
 * <ul>
 *   <li>{@code tournament} — {@link de.vvwt.tm.tournament.PhaseLifecycleService}, {@link
 *       de.vvwt.tm.tournament.Phase}, {@link de.vvwt.tm.tournament.TournamentRepository}, and other
 *       tournament-context public types consumed by the orchestrator (E55S04).
 *   <li>{@code tournament::draft} — {@link de.vvwt.tm.tournament.draft.DraftConfig}, {@link
 *       de.vvwt.tm.tournament.draft.DraftSection} consumed by {@link
 *       de.vvwt.tm.phaselifecycle.DraftApplicationOrchestrator} (E55S06, Option C, DEC-64 D-11).
 *       The {@code tournament.draft} named interface (DEC-35) is explicitly declared here to
 *       satisfy Spring Modulith boundary enforcement — a plain {@code "tournament"} dep covers the
 *       root package only, not its named-interface sub-packages.
 *   <li>{@code slotopt} — {@link de.vvwt.tm.slotopt.SlotOptimizationClient} invoked by the
 *       orchestrator for Leg-1/2/3 routing (E55S04).
 *   <li>{@code tenant} — {@link de.vvwt.tm.tenant.TenantContextResolver} for per-tenant DataSource
 *       routing (E55S04).
 * </ul>
 *
 * <p>Authorizing decisions: DEC-21 (Spring Modulith), DEC-22 (TDD Iron Law), DEC-35 (package
 * layout), DEC-40 (Primary-Adapter-Isolation — no controllers here), DEC-58 (universal interface
 * mandate), DEC-64 (Saga-Orchestrator architecture pivot).
 *
 * @since E55S01
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"tournament", "tournament::draft", "slotopt", "tenant"})
package de.vvwt.tm.phaselifecycle;
