package de.vvwt.info.governance;

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
 * Build-time interface-mandate enforcement guard (E57S04, DEC-72 Clause E-ext).
 *
 * <p>Asserts that every self-created Spring component in the {@code vvwt-info-server} Maven module
 * is addressable through a public first-party interface — satisfying DEC-58 Clause A (as amended by
 * DEC-72 Clause A-ext).
 *
 * <p>For the full predicate specification, see the corresponding class in {@code vvwt-tm-web}.
 *
 * @see <a href="DEC-58">DEC-58</a>
 * @see <a href="DEC-72">DEC-72</a>
 * @see <a href="E57S04">E57S04</a>
 */
class InterfaceMandateGuardTest {

    private static final String APP_PACKAGE_ROOT = "de.vvwt.info";

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

    private static final Set<String> FRAMEWORK_CONFIG_INTERFACE_NAMES =
            Set.of(
                    "org.springframework.web.servlet.config.annotation.WebMvcConfigurer",
                    "org.springframework.web.servlet.HandlerInterceptor",
                    "org.springframework.web.socket.config.annotation.WebSocketConfigurer",
                    "jakarta.servlet.Filter",
                    "org.springframework.web.socket.WebSocketHandler",
                    "org.springframework.web.socket.handler.AbstractWebSocketHandler",
                    "org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport");

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

    private static final Set<String> SPRING_DATA_REPOSITORY_NAMES =
            Set.of(
                    "org.springframework.data.repository.Repository",
                    "org.springframework.data.repository.CrudRepository",
                    "org.springframework.data.repository.PagingAndSortingRepository",
                    "org.springframework.data.repository.ListCrudRepository",
                    "org.springframework.data.repository.ListPagingAndSortingRepository",
                    "org.springframework.data.jpa.repository.JpaRepository");

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

    private static final class SpringComponentPredicate
            extends com.tngtech.archunit.base.DescribedPredicate<JavaClass> {

        SpringComponentPredicate() {
            super(
                    "are self-created Spring components subject to the DEC-58/DEC-72 interface"
                            + " mandate");
        }

        @Override
        public boolean test(JavaClass javaClass) {
            if (javaClass.isInterface()
                    || javaClass.getModifiers().contains(JavaModifier.ABSTRACT)
                    || javaClass.isAnnotation()
                    || javaClass.isAnonymousClass()
                    || javaClass.isMemberClass()) {
                return false;
            }

            boolean hasStereotype =
                    javaClass.isMetaAnnotatedWith(SERVICE)
                            || javaClass.isMetaAnnotatedWith(COMPONENT)
                            || javaClass.isMetaAnnotatedWith(REPOSITORY);
            if (!hasStereotype) {
                return false;
            }

            if (javaClass.isMetaAnnotatedWith(REST_CONTROLLER)
                    || javaClass.isMetaAnnotatedWith(CONTROLLER)) {
                return false;
            }

            if (javaClass.isMetaAnnotatedWith(CONFIGURATION)) {
                return false;
            }

            if (javaClass.isAnnotatedWith(CONFIGURATION_PROPERTIES)
                    || javaClass.isMetaAnnotatedWith(CONFIGURATION_PROPERTIES)) {
                return false;
            }

            if (javaClass.isMetaAnnotatedWith(CONTROLLER_ADVICE)
                    || javaClass.isMetaAnnotatedWith(REST_CONTROLLER_ADVICE)) {
                return false;
            }

            if (implementsSpringDataRepository(javaClass)) {
                return false;
            }

            if (isFrameworkConfigOnlyAdapter(javaClass)) {
                return false;
            }

            return true;
        }

        private boolean implementsSpringDataRepository(JavaClass javaClass) {
            return javaClass.getAllRawInterfaces().stream()
                    .anyMatch(iface -> SPRING_DATA_REPOSITORY_NAMES.contains(iface.getName()));
        }

        private boolean isFrameworkConfigOnlyAdapter(JavaClass javaClass) {
            boolean implementsFrameworkConfigInterface =
                    javaClass.getAllRawInterfaces().stream()
                            .anyMatch(
                                    iface ->
                                            FRAMEWORK_CONFIG_INTERFACE_NAMES.contains(
                                                    iface.getName()));

            if (!implementsFrameworkConfigInterface) {
                return false;
            }

            Set<String> frameworkMethodSignatures =
                    javaClass.getAllRawInterfaces().stream()
                            .filter(
                                    iface ->
                                            FRAMEWORK_CONFIG_INTERFACE_NAMES.contains(
                                                    iface.getName()))
                            .flatMap(iface -> iface.getMethods().stream())
                            .map(m -> m.getName() + "/" + m.getRawParameterTypes().size())
                            .collect(Collectors.toSet());

            for (JavaMethod method : javaClass.getMethods()) {
                if (!method.getOwner().equals(javaClass)) {
                    continue;
                }
                if (method.getModifiers().contains(JavaModifier.STATIC)) {
                    continue;
                }
                String methodSig = method.getName() + "/" + method.getRawParameterTypes().size();
                if (!frameworkMethodSignatures.contains(methodSig)) {
                    return false;
                }
            }

            return true;
        }
    }

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
}
