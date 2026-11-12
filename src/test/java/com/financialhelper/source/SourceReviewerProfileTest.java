package com.financialhelper.source;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SourceReviewerProfileTest {
    @Test
    void controllerIsAvailableInPersistentLocalDemoOnlyWhenProfileIsSelected() {
        try (var demo = new AnnotationConfigApplicationContext()) {
            demo.getEnvironment().setActiveProfiles("official-local-demo");
            demo.registerBean(SourceReviewerService.class, () -> mock(SourceReviewerService.class));
            demo.register(SourceReviewerController.class);
            demo.refresh();
            assertThat(demo.getBeansOfType(SourceReviewerController.class)).hasSize(1);
        }
        try (var ordinary = new AnnotationConfigApplicationContext()) {
            ordinary.registerBean(SourceReviewerService.class, () -> mock(SourceReviewerService.class));
            ordinary.register(SourceReviewerController.class);
            ordinary.refresh();
            assertThat(ordinary.getBeansOfType(SourceReviewerController.class)).isEmpty();
        }
    }
}
