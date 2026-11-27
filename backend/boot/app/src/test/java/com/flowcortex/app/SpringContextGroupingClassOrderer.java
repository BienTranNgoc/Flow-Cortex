package com.flowcortex.app;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.ClassDescriptor;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.ClassOrdererContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Orders test classes by their effective Spring TestContext signature so tests
 * sharing the same cached application context run back-to-back, minimizing
 * context cache evictions under {@code spring.test.context.cache.maxSize=8}.
 */
public final class SpringContextGroupingClassOrderer implements ClassOrderer {

    private static final Set<String> MOCK_BEAN_ANNOTATIONS = Set.of(
        "MockitoBean",
        "MockitoSpyBean",
        "MockBean",
        "SpyBean"
    );

    @Override
    public void orderClasses(ClassOrdererContext context) {
        context.getClassDescriptors().sort(Comparator.comparing(SpringContextGroupingClassOrderer::contextKey));
    }

    static String contextKey(ClassDescriptor descriptor) {
        Class<?> testClass = descriptor.getTestClass();
        SpringBootTest bootTest = AnnotatedElementUtils.findMergedAnnotation(testClass, SpringBootTest.class);
        if (bootTest == null) {
            return "0|" + testClass.getName();
        }

        StringBuilder key = new StringBuilder("1|");
        key.append(bootTest.webEnvironment().name()).append('|');
        key.append(sortedJoined(bootTest.properties())).append('|');
        key.append(sortedClassNames(bootTest.classes())).append('|');

        ActiveProfiles profiles = AnnotatedElementUtils.findMergedAnnotation(testClass, ActiveProfiles.class);
        if (profiles != null) {
            key.append("p=").append(sortedJoined(profiles.profiles()));
        }
        key.append('|');

        TestPropertySource propertySource = AnnotatedElementUtils.findMergedAnnotation(testClass, TestPropertySource.class);
        if (propertySource != null) {
            key.append("tps=").append(sortedJoined(propertySource.locations()))
                .append(':').append(sortedJoined(propertySource.properties()));
        }
        key.append('|');

        String imports = AnnotatedElementUtils.findAllMergedAnnotations(testClass, Import.class).stream()
            .flatMap(ann -> Arrays.stream(ann.value()))
            .map(Class::getName)
            .distinct()
            .sorted()
            .collect(Collectors.joining(","));
        key.append("imp=").append(imports).append('|');

        key.append("dps=").append(dynamicPropertySources(testClass)).append('|');
        key.append("mocks=").append(mockBeanOverrides(testClass)).append('|');
        key.append("tcfg=").append(hasNestedTestConfig(testClass) ? testClass.getName() : "").append('|');
        key.append("super=").append(superclassChain(testClass)).append('|');
        key.append(testClass.getName());
        return key.toString();
    }

    private static String sortedJoined(String[] values) {
        if (values == null || values.length == 0) {
            return "";
        }
        return Arrays.stream(values).sorted().collect(Collectors.joining(","));
    }

    private static String sortedClassNames(Class<?>[] classes) {
        if (classes == null || classes.length == 0) {
            return "";
        }
        return Arrays.stream(classes).map(Class::getName).sorted().collect(Collectors.joining(","));
    }

    private static String dynamicPropertySources(Class<?> testClass) {
        List<String> owners = new ArrayList<>();
        for (Class<?> current = testClass; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.isAnnotationPresent(DynamicPropertySource.class)) {
                    owners.add(current.getName() + "#" + method.getName());
                }
            }
        }
        return owners.stream().sorted().collect(Collectors.joining(","));
    }

    private static String mockBeanOverrides(Class<?> testClass) {
        List<String> mocks = new ArrayList<>();
        for (Class<?> current = testClass; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                for (Annotation annotation : field.getAnnotations()) {
                    String simpleName = annotation.annotationType().getSimpleName();
                    if (MOCK_BEAN_ANNOTATIONS.contains(simpleName)) {
                        mocks.add(simpleName + ":" + field.getType().getName());
                    }
                }
            }
        }
        return mocks.stream().sorted().collect(Collectors.joining(","));
    }

    private static boolean hasNestedTestConfig(Class<?> testClass) {
        for (Class<?> nested : testClass.getDeclaredClasses()) {
            if (AnnotatedElementUtils.hasAnnotation(nested, TestConfiguration.class)) {
                return true;
            }
        }
        return false;
    }

    private static String superclassChain(Class<?> testClass) {
        List<String> parents = new ArrayList<>();
        for (Class<?> current = testClass.getSuperclass(); current != null && current != Object.class; current = current.getSuperclass()) {
            parents.add(current.getName());
        }
        return String.join(">", parents);
    }
}
