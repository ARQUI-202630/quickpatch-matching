package co.quickpatch.matching.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Reglas de dependencia entre capas de SDD 10.3.2 (SCRUM-648) aplicadas a Matching. Revisan lo que el compilador
 * no impide en Java, donde las cuatro capas comparten un mismo módulo.
 */
class ArchitectureTest {

    private static final String BASE = "co.quickpatch.matching";
    private static final JavaClasses CLASES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE);

    @Test
    void domainNoDependeDeOtrasCapasNiDeSpring() {
        noClasses().that().resideInAPackage(BASE + ".domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        BASE + ".application..", BASE + ".infrastructure..", BASE + ".api..", "org.springframework..")
                .check(CLASES);
    }

    @Test
    void applicationNoDependeDeInfrastructureApiNiDeJdbcKafkaOWeb() {
        noClasses().that().resideInAPackage(BASE + ".application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        BASE + ".infrastructure..", BASE + ".api..",
                        "org.springframework.jdbc..", "org.springframework.kafka..", "org.springframework.web..")
                .check(CLASES);
    }

    @Test
    void apiNoDependeDeInfrastructure() {
        noClasses().that().resideInAPackage(BASE + ".api..")
                .should().dependOnClassesThat().resideInAPackage(BASE + ".infrastructure..")
                .check(CLASES);
    }

    @Test
    void infrastructureNoDependeDelApi() {
        noClasses().that().resideInAPackage(BASE + ".infrastructure..")
                .should().dependOnClassesThat().resideInAPackage(BASE + ".api..")
                .check(CLASES);
    }

    @Test
    void losPaquetesDeApplicationNoFormanCiclos() {
        slices().matching(BASE + ".application.(*)..").should().beFreeOfCycles().check(CLASES);
    }
}
