package Vystorm.vystorm_core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.mockito.MockedStatic;

/**
 * Every manager part listed in {@link ManagerParts} mirrors its static class completely and delegates each call
 * unchanged. Runs without a server: the static class is mocked, so only the hand-over is checked.
 */
class ManagerMirrorTest {
    @TestFactory
    Stream<DynamicTest> givenStaticClass_whenManagerPartInspected_thenEveryPublicStaticIsMirroredOrListed() {
        return ManagerParts.ALL.stream().map(part -> dynamicTest(part.access() + " mirrors " + part.statics().getSimpleName(), () -> {
            Set<String> mirrored = methods(part.manager()).stream().map(ManagerMirrorTest::signature).collect(Collectors.toCollection(TreeSet::new));
            Set<String> statics = new TreeSet<>(), unlisted = new TreeSet<>();
            for (Method method : part.statics().getDeclaredMethods()) {
                int m = method.getModifiers();
                if (method.isSynthetic() || !Modifier.isPublic(m) || !Modifier.isStatic(m)) continue;
                statics.add(signature(method));
                if (!mirrored.contains(signature(method)) && !part.notMirrored().contains(method.getName())) unlisted.add(signature(method));
            }
            assertTrue(unlisted.isEmpty(), part.statics().getSimpleName() + " has public static methods that " + part.access()
                    + " does not offer. Add them to " + part.manager().getSimpleName() + " or list them as not mirrored: " + unlisted);
            Set<String> extra = new TreeSet<>(mirrored);
            extra.removeAll(statics);
            assertTrue(extra.isEmpty(), part.manager().getSimpleName() + " has methods without a static twin: " + extra);
            for (String name : part.notMirrored())
                assertTrue(statics.stream().anyMatch(s -> s.startsWith(name + "(")), "not-mirrored entry " + name + " does not exist any more");
        }));
    }

    @TestFactory
    Stream<DynamicTest> givenDefaultPart_whenEveryMethodCalled_thenTheStaticMethodReceivesTheSameArguments() {
        return ManagerParts.ALL.stream().map(part -> dynamicTest(part.access() + " delegates to " + part.statics().getSimpleName(), () -> {
            Object target = part.manager().getField("DEFAULT").get(null);
            assertNotNull(target);
            List<Method> methods = methods(part.manager());
            assertTrue(!methods.isEmpty());
            try (MockedStatic<?> statics = mockStatic(part.statics())) {
                for (Method method : methods) {
                    assertTrue(method.isDefault(), method + " needs a default body");
                    Method twin = part.statics().getMethod(method.getName(), method.getParameterTypes());
                    assertEquals(twin.getReturnType(), method.getReturnType(), "return type of " + method);
                    Object[] arguments = Arrays.stream(method.getParameterTypes()).map(ManagerMirrorTest::sample).toArray();
                    invoke(method, target, arguments);
                    statics.verify(() -> invoke(twin, null, arguments), times(1));
                    statics.clearInvocations();
                }
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> givenPartInterface_whenLoaded_thenDefaultInstanceIsShared() {
        return ManagerParts.ALL.stream().map(part -> dynamicTest(part.manager().getSimpleName() + ".DEFAULT", () ->
                assertSame(part.manager().getField("DEFAULT").get(null), part.manager().getField("DEFAULT").get(null))));
    }

    private static List<Method> methods(Class<?> manager) {
        List<Method> result = new ArrayList<>();
        for (Method method : manager.getDeclaredMethods()) if (!method.isSynthetic() && !Modifier.isStatic(method.getModifiers())) result.add(method);
        return result;
    }

    private static String signature(Method method) {
        return method.getName() + Arrays.stream(method.getParameterTypes()).map(Class::getTypeName).collect(Collectors.joining(",", "(", ")"))
                + method.getReturnType().getTypeName();
    }

    private static void invoke(Method method, Object target, Object[] arguments) {
        try {
            method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw new AssertionError(method + " threw", failure.getCause());
        } catch (IllegalAccessException failure) {
            throw new AssertionError(method + " is not accessible", failure);
        }
    }

    /** A value that passes through unchanged: zero for primitives, an empty array for arrays, null otherwise. */
    private static Object sample(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == char.class) return 'x';
        if (type == byte.class) return (byte) 1;
        if (type == short.class) return (short) 2;
        if (type == int.class) return 3;
        if (type == long.class) return 4L;
        if (type == float.class) return 5f;
        if (type == double.class) return 6d;
        if (type.isArray()) return Array.newInstance(type.getComponentType(), 0);
        if (type == String.class) return "text";
        return null;
    }
}
