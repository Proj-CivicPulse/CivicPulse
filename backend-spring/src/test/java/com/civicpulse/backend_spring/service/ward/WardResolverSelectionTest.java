package com.civicpulse.backend_spring.service.ward;

import com.civicpulse.backend_spring.config.AppProperties;
import com.civicpulse.backend_spring.repository.WardRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which {@link WardResolver} is actually wired, for each value of
 * {@code app.ward-resolver}.
 *
 * <p>This exists because the selection is made by {@code @ConditionalOnProperty}
 * — configuration, not code — and nothing else in the test suite would notice if
 * it silently picked the wrong one. The failure mode is quiet and expensive:
 * every complaint keeps getting a ward, it is simply the approximate one, and no
 * error appears anywhere.
 *
 * <p>It also pins the thing that would be worst to get wrong: that exactly ONE
 * resolver is ever present. Two beans implementing the interface is a startup
 * failure; zero is a startup failure; the dangerous case is the wrong one.
 *
 * <p>Uses {@link ApplicationContextRunner} rather than {@code @SpringBootTest}
 * so it runs in milliseconds and needs no database — the question is purely
 * about conditional wiring.
 */
class WardResolverSelectionTest {

    /**
     * Imports the resolver CLASSES rather than declaring @Bean methods for
     * them. The conditions live on the classes, and a @Bean factory method
     * would bypass them entirely — which would make this test assert nothing.
     */
    private ApplicationContextRunner scanned(String... properties) {
        return new ApplicationContextRunner()
                .withBean(WardRepository.class, () -> Mockito.mock(WardRepository.class))
                .withBean(AppProperties.class, AppProperties::new)
                .withPropertyValues(properties)
                .withConfiguration(AutoConfigurations.of())
                .withUserConfiguration(ScannedResolvers.class);
    }

    @Configuration
    @org.springframework.context.annotation.Import({
            PostGisWardResolver.class, CentroidWardResolver.class})
    static class ScannedResolvers {
    }

    @Test
    @DisplayName("unset -> PostGIS, because that is the deployed default")
    void defaultsToPostGis() {
        scanned().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(WardResolver.class)).hasSize(1);
            assertThat(context.getBean(WardResolver.class))
                    .isInstanceOf(PostGisWardResolver.class);
        });
    }

    @Test
    @DisplayName("app.ward-resolver=postgis -> PostGIS, and the centroid bean is absent")
    void explicitPostGis() {
        scanned("app.ward-resolver=postgis").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(WardResolver.class)).hasSize(1);
            assertThat(context.getBean(WardResolver.class))
                    .isInstanceOf(PostGisWardResolver.class);
            // Not merely unused — not constructed at all, so it cannot be
            // injected by accident somewhere that asks for the concrete type.
            assertThat(context).doesNotHaveBean(CentroidWardResolver.class);
        });
    }

    @Test
    @DisplayName("app.ward-resolver=centroid -> centroid ONLY as an explicit opt-in")
    void explicitCentroid() {
        scanned("app.ward-resolver=centroid").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(WardResolver.class)).hasSize(1);
            assertThat(context.getBean(WardResolver.class))
                    .isInstanceOf(CentroidWardResolver.class);
            assertThat(context).doesNotHaveBean(PostGisWardResolver.class);
        });
    }

    @Test
    @DisplayName("an unrecognised value yields NO resolver, so startup fails loudly")
    void unknownValueFailsClosed() {
        // A typo in WARD_RESOLVER must not silently fall back to either
        // implementation. Failing to start is the correct outcome: the operator
        // finds out immediately instead of discovering months of approximate
        // ward assignment later.
        scanned("app.ward-resolver=nonsense").run(context -> {
            assertThat(context.getBeansOfType(WardResolver.class)).isEmpty();
        });
    }

    @Test
    @DisplayName("the default in AppProperties agrees with the @ConditionalOnProperty default")
    void propertyDefaultMatchesWiring() {
        // These are two independent declarations of "what happens when unset":
        // matchIfMissing on PostGisWardResolver, and the field initialiser here.
        // If they ever disagree, /wards would report one implementation while
        // another did the work.
        assertThat(new AppProperties().getWardResolver()).isEqualTo("postgis");
    }
}
