// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.governance;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Build-time interface-mandate enforcement guard (E57S05, DEC-72 Clause E-ext).
 *
 * <p>Asserts that every self-created Spring component in the {@code vvwt-tm-web} Maven module is
 * addressable through a public first-party interface — satisfying DEC-58 Clause A (as amended by
 * DEC-72 Clause A-ext).
 *
 * <h2>Covered trigger (DEC-72 Clause A-ext)</h2>
 *
 * <p>A class is a <em>covered component</em> iff it:
 *
 * <ol>
 *   <li>Is annotated with {@code @Service}, {@code @Component} (incl. meta-annotations such as
 *       hand-authored {@code @Repository}), or has a class-level Spring stereotype annotation
 *       recognised by the Spring container as a component-scan candidate.
 *   <li>Is concrete (not abstract, not an interface).
 *   <li>Is NOT excluded by DEC-58 Clause D or DEC-72 Clause D-ext (see below).
 * </ol>
 *
 * <h2>Exclusion set (DEC-58 Clause D + DEC-72 Clause D-ext)</h2>
 *
 * <ul>
 *   <li>Clause D: {@code @RestController} / {@code @Controller} (primary adapters per DEC-40).
 *   <li>Clause D: {@code @Configuration} classes (bean factories, not service-shaped).
 *   <li>Clause C: Spring Data {@code Repository} / {@code CrudRepository} implementors (the
 *       interface IS the port).
 *   <li>D-ext-1: {@code @ConfigurationProperties} holders (typed config carriers).
 *   <li>D-ext-2: {@code @ControllerAdvice} / {@code @RestControllerAdvice} (controller-family
 *       primary adapters).
 *   <li>D-ext-3: Framework-config-only adapters — a {@code @Component} that implements at least one
 *       Spring-framework config interface ({@code WebMvcConfigurer}, {@code HandlerInterceptor},
 *       {@code WebSocketConfigurer}, {@code Filter}, {@code WebSocketHandler}) AND declares no
 *       {@code public} method beyond that framework interface's contract.
 * </ul>
 *
 * <h2>Compliance</h2>
 *
 * <p>A covered component is <em>compliant</em> iff it implements at least one public first-party
 * interface (an interface whose package is within the scanned application package root, i.e., does
 * NOT start with a Spring-framework or third-party package prefix).
 *
 * @see <a href="DEC-58">DEC-58</a>
 * @see <a href="DEC-72">DEC-72</a>
 * @see <a href="E57S05">E57S05</a>
 */
class InterfaceMandateGuardTest {

    // -------------------------------------------------------------------------
    // Package roots
    // -------------------------------------------------------------------------

    /** Root package for vvwt-tm-web first-party classes. */
    private static final String APP_PACKAGE_ROOT = "de.vvwt.tm";

    // -------------------------------------------------------------------------
    // Framework package prefixes — interfaces from these packages are NOT first-party
    // -------------------------------------------------------------------------

    private static final Set<String> FRAMEWORK_PACKAGE_PREFIXES =
            Set.of(
                    "org.springframework.",
                    "org.hibernate.",
                    "jakarta.",
                    "java.",
                    "javax.",
                    "com.fasterxml.",
                    "io.github.",
                    "com.github.",
                    "net.jqwik.",
                    "org.junit.",
                    "org.slf4j.",
                    "org.assertj.",
                    "com.tngtech.",
                    "org.testcontainers.",
                    "org.htmlunit.",
                    "info.picocli.",
                    "org.thymeleaf.",
                    "org.flywaydb.",
                    "com.zaxxer.",
                    "io.micrometer.",
                    "de.vvwt.slotopt.worker.");

    // -------------------------------------------------------------------------
    // Framework config interface names (fully-qualified) — for D-ext-3
    // -------------------------------------------------------------------------

    private static final Set<String> FRAMEWORK_CONFIG_INTERFACE_NAMES =
            Set.of(
                    "org.springframework.web.servlet.config.annotation.WebMvcConfigurer",
                    "org.springframework.web.servlet.HandlerInterceptor",
                    "org.springframework.web.socket.config.annotation.WebSocketConfigurer",
                    "jakarta.servlet.Filter",
                    "org.springframework.web.socket.WebSocketHandler",
                    "org.springframework.web.socket.handler.AbstractWebSocketHandler",
                    "org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport");

    // -------------------------------------------------------------------------
    // Annotation names
    // -------------------------------------------------------------------------

    private static final String SERVICE = "org.springframework.stereotype.Service";
    private static final String COMPONENT = "org.springframework.stereotype.Component";
    private static final String REPOSITORY = "org.springframework.stereotype.Repository";
    private static final String CONTROLLER = "org.springframework.stereotype.Controller";
    private static final String REST_CONTROLLER =
            "org.springframework.web.bind.annotation.RestController";
    private static final String CONFIGURATION =
            "org.springframework.context.annotation.Configuration";
    private static final String CONFIGURATION_PROPERTIES =
            "org.springframework.boot.context.properties.ConfigurationProperties";
    private static final String CONTROLLER_ADVICE =
            "org.springframework.web.bind.annotation.ControllerAdvice";
    private static final String REST_CONTROLLER_ADVICE =
            "org.springframework.web.bind.annotation.RestControllerAdvice";

    // Spring Data base interface names
    private static final Set<String> SPRING_DATA_REPOSITORY_NAMES =
            Set.of(
                    "org.springframework.data.repository.Repository",
                    "org.springframework.data.repository.CrudRepository",
                    "org.springframework.data.repository.PagingAndSortingRepository",
                    "org.springframework.data.repository.ListCrudRepository",
                    "org.springframework.data.repository.ListPagingAndSortingRepository",
                    "org.springframework.data.jpa.repository.JpaRepository",
                    "org.springframework.data.jdbc.repository.query.Query");

    private static final String BEAN = "org.springframework.context.annotation.Bean";

    // -------------------------------------------------------------------------
    // Guard tests
    // -------------------------------------------------------------------------

    @Test
    void selfCreatedSpringComponentsMustHaveFirstPartyInterface() {
        var classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages(APP_PACKAGE_ROOT);

        ArchRule rule =
                noClasses()
                        .that(new SpringComponentPredicate())
                        .should(new LacksFirstPartyInterfaceCondition())
                        .as(
                                "DEC-58/DEC-72 interface mandate: every self-created Spring"
                                        + " component in '"
                                        + APP_PACKAGE_ROOT
                                        + "' must implement at least one public first-party"
                                        + " interface. Add a '{Foo}' interface in the"
                                        + " bounded-context root package and make the"
                                        + " implementation 'Default{Foo}'. See DEC-58 + DEC-72.");

        rule.check(classes);
    }

    // -------------------------------------------------------------------------
    // Predicate: identifies COVERED Spring components (DEC-72 Clause A-ext minus exclusions)
    // -------------------------------------------------------------------------

    /**
     * Identifies a class as a covered Spring component subject to the interface mandate.
     *
     * <p>A class is covered iff it:
     *
     * <ol>
     *   <li>Has a Spring stereotype annotation ({@code @Service}, {@code @Component}, or
     *       {@code @Repository}) — checked transitively via meta-annotations (ArchUnit resolves
     *       meta-annotations for {@code isMetaAnnotatedWith}).
     *   <li>Is concrete (not abstract, not an interface).
     *   <li>Is NOT excluded by any Clause D / Clause D-ext rule.
     * </ol>
     */
    private static final class SpringComponentPredicate
            extends com.tngtech.archunit.base.DescribedPredicate<JavaClass> {

        SpringComponentPredicate() {
            super(
                    "are self-created Spring components subject to the DEC-58/DEC-72 interface"
                            + " mandate");
        }

        @Override
        public boolean test(JavaClass javaClass) {
            // 1. Must be a concrete class (not abstract, not interface, not enum-special)
            if (javaClass.isInterface()
                    || javaClass.getModifiers().contains(JavaModifier.ABSTRACT)
                    || javaClass.isAnnotation()
                    || javaClass.isAnonymousClass()
                    || javaClass.isMemberClass()) {
                return false;
            }

            // 2. Must have a Spring stereotype (Service, Component, Repository — incl.
            // meta-annotations)
            boolean hasStereotype =
                    javaClass.isMetaAnnotatedWith(SERVICE)
                            || javaClass.isMetaAnnotatedWith(COMPONENT)
                            || javaClass.isMetaAnnotatedWith(REPOSITORY);
            if (!hasStereotype) {
                return false;
            }

            // 3. Clause D exclusions — @RestController / @Controller
            if (javaClass.isMetaAnnotatedWith(REST_CONTROLLER)
                    || javaClass.isMetaAnnotatedWith(CONTROLLER)) {
                return false;
            }

            // 4. Clause D exclusions — @Configuration
            if (javaClass.isMetaAnnotatedWith(CONFIGURATION)) {
                return false;
            }

            // 5. D-ext-1 — @ConfigurationProperties holders (incl. composite @Component +
            // @ConfigurationProperties)
            if (javaClass.isAnnotatedWith(CONFIGURATION_PROPERTIES)
                    || javaClass.isMetaAnnotatedWith(CONFIGURATION_PROPERTIES)) {
                return false;
            }

            // 6. D-ext-2 — @ControllerAdvice / @RestControllerAdvice
            if (javaClass.isMetaAnnotatedWith(CONTROLLER_ADVICE)
                    || javaClass.isMetaAnnotatedWith(REST_CONTROLLER_ADVICE)) {
                return false;
            }

            // 7. Clause C — Spring Data repository (implements Repository / CrudRepository
            // hierarchy)
            if (implementsSpringDataRepository(javaClass)) {
                return false;
            }

            // 8. D-ext-3 — framework-config-only adapter: implements at least one
            // Spring-framework config interface AND has no first-party public method beyond those
            // interfaces
            if (isFrameworkConfigOnlyAdapter(javaClass)) {
                return false;
            }

            return true;
        }

        /**
         * Returns true if the class implements a Spring Data repository interface (Clause C
         * exclusion).
         */
        private boolean implementsSpringDataRepository(JavaClass javaClass) {
            return javaClass.getAllRawInterfaces().stream()
                    .anyMatch(iface -> SPRING_DATA_REPOSITORY_NAMES.contains(iface.getName()));
        }

        /**
         * Returns true if the class is a framework-config-only adapter (DEC-72 Clause D-ext-3):
         *
         * <ul>
         *   <li>Implements at least one Spring-framework config interface ({@code
         *       WebMvcConfigurer}, {@code HandlerInterceptor}, {@code WebSocketConfigurer}, {@code
         *       Filter}, {@code WebSocketHandler}), AND
         *   <li>Declares no {@code public} method that is NOT defined on one of those framework
         *       config interfaces.
         * </ul>
         *
         * <p>Conservative: if any ambiguity, we return false (i.e., we include the class as
         * covered). This implements the "conservatively over-flags rather than false-PASSes"
         * requirement from DEC-72.
         */
        private boolean isFrameworkConfigOnlyAdapter(JavaClass javaClass) {
            // Check if the class implements at least one framework config interface
            boolean implementsFrameworkConfigInterface =
                    javaClass.getAllRawInterfaces().stream()
                            .anyMatch(
                                    iface ->
                                            FRAMEWORK_CONFIG_INTERFACE_NAMES.contains(
                                                    iface.getName()));

            if (!implementsFrameworkConfigInterface) {
                return false;
            }

            // Collect all method signatures defined on the framework config interfaces that
            // this class implements
            Set<String> frameworkMethodSignatures =
                    javaClass.getAllRawInterfaces().stream()
                            .filter(
                                    iface ->
                                            FRAMEWORK_CONFIG_INTERFACE_NAMES.contains(
                                                    iface.getName()))
                            .flatMap(iface -> iface.getMethods().stream())
                            .map(m -> m.getName() + "/" + m.getRawParameterTypes().size())
                            .collect(Collectors.toSet());

            // Check that every public method declared directly on this class either:
            // (a) has a matching signature in the framework config interface methods, or
            // (b) is a static method (static helpers are not service contract methods)
            for (JavaMethod method : javaClass.getMethods()) {
                // Only look at methods declared directly on this class (not inherited)
                if (!method.getOwner().equals(javaClass)) {
                    continue;
                }
                // Skip static methods
                if (method.getModifiers().contains(JavaModifier.STATIC)) {
                    continue;
                }
                // Check if this method matches any framework config interface method
                String methodSig = method.getName() + "/" + method.getRawParameterTypes().size();
                if (!frameworkMethodSignatures.contains(methodSig)) {
                    // This class has an additional first-party public method → NOT D-ext-3
                    return false;
                }
            }

            // All public methods are framework config interface overrides
            return true;
        }
    }

    // -------------------------------------------------------------------------
    // Condition: checks that a covered component implements a first-party interface
    // -------------------------------------------------------------------------

    /**
     * ArchUnit condition that FAILS when a covered Spring component does NOT implement at least one
     * public first-party interface.
     *
     * <p>A "first-party interface" is an interface whose fully-qualified package name does NOT
     * start with any of the known framework / third-party package prefixes (i.e., it lives in
     * {@code de.vvwt.*} or other first-party code).
     */
    private static final class LacksFirstPartyInterfaceCondition extends ArchCondition<JavaClass> {

        LacksFirstPartyInterfaceCondition() {
            super(
                    "implement at least one public first-party interface (DEC-58/DEC-72 Clause"
                            + " A-ext)");
        }

        @Override
        public void check(JavaClass javaClass, ConditionEvents events) {
            boolean hasFirstPartyInterface =
                    javaClass.getAllRawInterfaces().stream()
                            .anyMatch(
                                    iface ->
                                            iface.getModifiers().contains(JavaModifier.PUBLIC)
                                                    && isFirstPartyInterface(iface));

            if (!hasFirstPartyInterface) {
                String message =
                        String.format(
                                "DEC-58/DEC-72 VIOLATION: %s is a Spring component that does not"
                                    + " implement any public first-party interface. Add a '{Foo}'"
                                    + " interface in the bounded-context root package and rename"
                                    + " the implementation to 'Default{Foo}'. See DEC-58 Clause A +"
                                    + " DEC-72 Clause A-ext.",
                                javaClass.getName());
                // noClasses().should(this): ArchUnit FAILS the rule when a SATISFIED event fires.
                // We want the rule to fail when the class lacks a first-party interface,
                // so we emit a SATISFIED event here (class satisfies "lacks first-party
                // interface").
                events.add(SimpleConditionEvent.satisfied(javaClass, message));
            } else {
                events.add(
                        SimpleConditionEvent.violated(
                                javaClass, javaClass.getName() + " has a first-party interface"));
            }
        }

        private boolean isFirstPartyInterface(JavaClass iface) {
            String ifaceName = iface.getName();
            for (String prefix : FRAMEWORK_PACKAGE_PREFIXES) {
                if (ifaceName.startsWith(prefix)) {
                    return false;
                }
            }
            return true;
        }
    }

    // -------------------------------------------------------------------------
    // Guard test — @Bean-factory-produced service beans (DEC-72 Clause A-ext)
    // -------------------------------------------------------------------------

    /**
     * Asserts that every concrete return type of a {@code @Bean} factory method declared in a
     * first-party {@code @Configuration} class implements at least one public first-party
     * interface.
     *
     * <p>A {@code @Bean} method is compliant iff its return type is either:
     *
     * <ul>
     *   <li>An interface type (the bean IS the interface — already compliant), OR
     *   <li>A concrete class that implements at least one public first-party interface.
     * </ul>
     *
     * <p>A concrete return type with no public first-party interface is a DEC-72 Clause A-ext
     * violation.
     */
    @Test
    void beanProducedServicesMustHaveFirstPartyInterface() {
        var classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages(APP_PACKAGE_ROOT);

        ArchRule rule =
                noClasses()
                        .that(new ConfigurationWithBeanMethodsPredicate())
                        .should(new HasBeanMethodWithConcreteReturnTypeLackingInterfaceCondition())
                        .as(
                                "DEC-58/DEC-72 Clause A-ext @Bean mandate: every @Bean factory"
                                        + " method in '"
                                        + APP_PACKAGE_ROOT
                                        + "' whose return type is a concrete first-party class must"
                                        + " produce a type that implements at least one public"
                                        + " first-party interface. See DEC-58 + DEC-72.");

        rule.check(classes);
    }

    /**
     * Identifies first-party {@code @Configuration} classes that have at least one {@code @Bean}
     * method.
     */
    private static final class ConfigurationWithBeanMethodsPredicate
            extends com.tngtech.archunit.base.DescribedPredicate<JavaClass> {

        ConfigurationWithBeanMethodsPredicate() {
            super("are first-party @Configuration classes with @Bean factory methods");
        }

        @Override
        public boolean test(JavaClass javaClass) {
            if (!javaClass.isMetaAnnotatedWith(CONFIGURATION)) {
                return false;
            }
            return javaClass.getMethods().stream()
                    .anyMatch(m -> m.isAnnotatedWith(BEAN) || m.isMetaAnnotatedWith(BEAN));
        }
    }

    /**
     * ArchUnit condition that fires a SATISFIED event when a {@code @Configuration} class contains
     * at least one {@code @Bean} method whose return type is a concrete first-party class lacking a
     * public first-party interface.
     */
    private final class HasBeanMethodWithConcreteReturnTypeLackingInterfaceCondition
            extends ArchCondition<JavaClass> {

        HasBeanMethodWithConcreteReturnTypeLackingInterfaceCondition() {
            super(
                    "have a @Bean method whose concrete return type lacks a public first-party"
                            + " interface (DEC-72 Clause A-ext)");
        }

        @Override
        public void check(JavaClass javaClass, ConditionEvents events) {
            for (JavaMethod method : javaClass.getMethods()) {
                if (!method.isAnnotatedWith(BEAN) && !method.isMetaAnnotatedWith(BEAN)) {
                    continue;
                }
                JavaClass returnType = method.getRawReturnType();
                if (returnType.isInterface()
                        || returnType.getModifiers().contains(JavaModifier.ABSTRACT)
                        || returnType.isAnnotation()) {
                    // Return type is already an interface / abstract / annotation — no mandate
                    continue;
                }
                // Check if return type is a first-party class (not a framework class)
                String returnTypeName = returnType.getName();
                boolean isFirstParty = true;
                for (String prefix : FRAMEWORK_PACKAGE_PREFIXES) {
                    if (returnTypeName.startsWith(prefix)) {
                        isFirstParty = false;
                        break;
                    }
                }
                if (!isFirstParty) {
                    continue;
                }
                // Apply DEC-58 Clause D exclusions to return type:
                // @RestController / @Controller (Clause D), @ControllerAdvice /
                // @RestControllerAdvice (D-ext-2)
                if (returnType.isMetaAnnotatedWith(REST_CONTROLLER)
                        || returnType.isMetaAnnotatedWith(CONTROLLER)
                        || returnType.isMetaAnnotatedWith(CONTROLLER_ADVICE)
                        || returnType.isMetaAnnotatedWith(REST_CONTROLLER_ADVICE)) {
                    continue;
                }
                // @ConfigurationProperties (D-ext-1)
                if (returnType.isAnnotatedWith(CONFIGURATION_PROPERTIES)
                        || returnType.isMetaAnnotatedWith(CONFIGURATION_PROPERTIES)) {
                    continue;
                }
                // Concrete first-party return type — check for a public first-party interface
                boolean hasFirstPartyInterface =
                        returnType.getAllRawInterfaces().stream()
                                .anyMatch(
                                        iface ->
                                                iface.getModifiers().contains(JavaModifier.PUBLIC)
                                                        && isFirstPartyInterface(iface));
                if (!hasFirstPartyInterface) {
                    String message =
                            String.format(
                                    "DEC-58/DEC-72 VIOLATION: @Bean method %s#%s returns concrete"
                                        + " first-party type %s that does not implement any public"
                                        + " first-party interface. Add a '{Foo}' interface and"
                                        + " change the @Bean return type to that interface. See"
                                        + " DEC-58 Clause A + DEC-72 Clause A-ext.",
                                    javaClass.getName(), method.getName(), returnTypeName);
                    // noClasses().should(this): SATISFIED event causes rule failure.
                    events.add(SimpleConditionEvent.satisfied(javaClass, message));
                }
            }
        }

        private boolean isFirstPartyInterface(JavaClass iface) {
            String ifaceName = iface.getName();
            for (String prefix : FRAMEWORK_PACKAGE_PREFIXES) {
                if (ifaceName.startsWith(prefix)) {
                    return false;
                }
            }
            return true;
        }
    }
}
