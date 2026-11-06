package com.onecare.backend.chatbot.knowledgebase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.onecare.backend.chatbot.query.ChatbotQueryController;

/**
 * API-02 safety net (no Spring context needed): finds EVERY controller under /api/chatbot/knowledge-base and checks that every
 * request handler carries exactly @PreAuthorize("hasAnyRole('DOCTOR','SUPER_ADMIN')"). Add a new endpoint and forget the annotation
 * and this test fails, instead of the endpoint silently being open to every logged-in user.
 */
class KnowledgeBaseAccessGuardTest {
    private static final String EXPECTED = "hasAnyRole('DOCTOR','SUPER_ADMIN')";
    private static final String KB_PATH = "/api/chatbot/knowledge-base";
    private static final String BASE_PACKAGE = "com.onecare";           // ADAPT: the root package of your application

    private static List<Class<?>> controllersUnder(String pathPrefix) throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> result = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            RequestMapping mapping = type.getAnnotation(RequestMapping.class);
            if (mapping != null && Arrays.stream(mapping.value()).anyMatch(p -> p.startsWith(pathPrefix))) result.add(type);
        }
        return result;
    }

    private static List<Method> handlers(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods()).filter(m -> AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class)).toList();
    }

    private static String normalised(String expression) { return expression.replaceAll("\\s+", ""); }

    @Test void every_knowledge_base_handler_requires_doctor_or_super_admin() throws Exception {
        List<Class<?>> controllers = controllersUnder(KB_PATH);
        assertFalse(controllers.isEmpty(), "No knowledge-base controller found: is BASE_PACKAGE right?");
        int checked = 0;
        for (Class<?> controller : controllers) {
            for (Method handler : handlers(controller)) {
                PreAuthorize onMethod = handler.getAnnotation(PreAuthorize.class);
                PreAuthorize onClass = controller.getAnnotation(PreAuthorize.class);
                PreAuthorize effective = onMethod != null ? onMethod : onClass;
                String where = controller.getSimpleName() + "." + handler.getName();
                assertNotNull(effective, where + " has no @PreAuthorize: it would be open to every logged-in user");
                assertEquals(EXPECTED, normalised(effective.value()), where + " has a different role rule");
                checked++;
            }
        }
        assertTrue(checked >= 1, "Expected the link submission handler, found " + checked);
        assertTrue(handlers(KnowledgeBaseController.class).stream()
                .anyMatch(method -> method.getName().equals("submitLink")), "Link submission handler is missing");
    }

    @Test void the_public_query_endpoint_must_stay_public() {
        assertNull(ChatbotQueryController.class.getAnnotation(PreAuthorize.class), "FR-043: the chat endpoint needs no login");
        for (Method handler : handlers(ChatbotQueryController.class)) {
            assertNull(handler.getAnnotation(PreAuthorize.class), handler.getName() + " must not require a role");
        }
    }
}
