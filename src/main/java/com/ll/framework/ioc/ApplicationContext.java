package com.ll.framework.ioc;

import com.ll.framework.ioc.annotations.Bean;
import com.ll.framework.ioc.annotations.Component;
import com.ll.framework.ioc.annotations.Configuration;
import com.ll.standard.util.Ut;
import org.reflections.Reflections;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 스프링 ApplicationContext 를 흉내 낸 IoC 컨테이너 v3.
 * <ul>
 *     <li>@Component(및 @Service·@Repository·@Configuration) 클래스 → 생성자 주입으로 빈 생성</li>
 *     <li>@Configuration 클래스의 @Bean 메서드 → 메서드 이름이 빈 이름, 파라미터는 다른 빈으로 채워 호출</li>
 * </ul>
 * 두 방식으로 만든 빈 모두 같은 genBean 으로 꺼내며 싱글톤이다.
 */
public class ApplicationContext {
    private final String basePackage;

    /** 빈 이름 → 빈 생성 정보 */
    private final Map<String, BeanDefinition> beanDefinitions = new LinkedHashMap<>();

    /** 빈 이름 → 만들어진 빈 (싱글톤 보관소) */
    private final Map<String, Object> beans = new HashMap<>();

    /** 지금 만드는 중인 빈 이름들. 순환 의존을 잡아내는 데 쓴다. */
    private final Set<String> beansInCreation = new LinkedHashSet<>();

    public ApplicationContext(String basePackage) {
        this.basePackage = basePackage;
    }

    public void init() {
        Reflections reflections = new Reflections(basePackage);

        scanComponents(reflections);
        scanBeanMethods(reflections);

        // 스프링처럼 싱글톤 빈은 컨테이너 초기화 때 미리 만들어 둔다.
        List.copyOf(beanDefinitions.keySet()).forEach(this::genBean);
    }

    @SuppressWarnings("unchecked")
    public <T> T genBean(String beanName) {
        Object bean = beans.get(beanName);

        if (bean == null) {
            bean = createBean(beanName);
            beans.put(beanName, bean);
        }

        return (T) bean;
    }

    private void scanComponents(Reflections reflections) {
        reflections.getTypesAnnotatedWith(Component.class).stream()
                .filter(this::isInstantiableClass)
                .forEach(cls -> register(Ut.str.lcfirst(cls.getSimpleName()), new ComponentDefinition(cls)));
    }

    private void scanBeanMethods(Reflections reflections) {
        reflections.getTypesAnnotatedWith(Configuration.class).stream()
                .filter(this::isInstantiableClass)
                .forEach(configClass -> {
                    String configBeanName = Ut.str.lcfirst(configClass.getSimpleName());

                    Arrays.stream(configClass.getDeclaredMethods())
                            .filter(method -> method.isAnnotationPresent(Bean.class))
                            .forEach(method -> register(method.getName(), new BeanMethodDefinition(configBeanName, method)));
                });
    }

    private void register(String beanName, BeanDefinition beanDefinition) {
        BeanDefinition old = beanDefinitions.putIfAbsent(beanName, beanDefinition);

        if (old != null) {
            throw new IllegalStateException("같은 이름의 빈이 이미 있습니다: " + beanName);
        }
    }

    /** @Service 같은 애너테이션 자체나 인터페이스·추상 클래스는 빈으로 만들 수 없으니 거른다. */
    private boolean isInstantiableClass(Class<?> cls) {
        return !cls.isAnnotation() && !cls.isInterface() && !Modifier.isAbstract(cls.getModifiers());
    }

    private Object createBean(String beanName) {
        BeanDefinition beanDefinition = beanDefinitions.get(beanName);

        if (beanDefinition == null) {
            throw new IllegalArgumentException("등록되지 않은 빈입니다: " + beanName);
        }

        if (!beansInCreation.add(beanName)) {
            throw new IllegalStateException("순환 의존이 있습니다: " + beansInCreation + " -> " + beanName);
        }

        try {
            return beanDefinition.create();
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("빈 생성 실패: " + beanName, e);
        } finally {
            beansInCreation.remove(beanName);
        }
    }

    private Object[] resolveDependencies(Executable executable) {
        return Arrays.stream(executable.getParameters())
                .map(parameter -> genBean(findBeanName(parameter)))
                .toArray();
    }

    /** 파라미터 타입에 맞는 빈 이름을 찾는다. 후보가 여럿이면 파라미터 이름과 같은 빈을 고른다. */
    private String findBeanName(Parameter parameter) {
        List<String> candidates = beanDefinitions.entrySet().stream()
                .filter(entry -> parameter.getType().isAssignableFrom(entry.getValue().type()))
                .map(Map.Entry::getKey)
                .toList();

        if (candidates.size() == 1) return candidates.getFirst();

        if (parameter.isNamePresent() && candidates.contains(parameter.getName())) {
            return parameter.getName();
        }

        if (candidates.isEmpty()) {
            throw new IllegalStateException("주입할 빈이 없습니다: " + parameter.getType().getName());
        }

        throw new IllegalStateException("주입할 빈이 여러 개입니다: " + parameter.getType().getName() + " " + candidates);
    }

    /** 빈 하나를 만드는 방법. 타입은 의존 빈을 타입으로 찾을 때 쓴다. */
    private interface BeanDefinition {
        Class<?> type();

        Object create() throws InstantiationException, IllegalAccessException, InvocationTargetException;
    }

    /** 컴포넌트 스캔으로 찾은 클래스: 파라미터가 가장 많은 생성자로 주입해 만든다. */
    private class ComponentDefinition implements BeanDefinition {
        private final Class<?> beanClass;

        ComponentDefinition(Class<?> beanClass) {
            this.beanClass = beanClass;
        }

        @Override
        public Class<?> type() {
            return beanClass;
        }

        @Override
        public Object create() throws InstantiationException, IllegalAccessException, InvocationTargetException {
            Constructor<?> constructor = Arrays.stream(beanClass.getDeclaredConstructors())
                    .max(Comparator.comparingInt(Constructor::getParameterCount))
                    .orElseThrow();

            Object[] args = resolveDependencies(constructor);

            constructor.setAccessible(true);
            return constructor.newInstance(args);
        }
    }

    /** @Configuration 클래스의 @Bean 메서드: 설정 빈에서 메서드를 호출한 반환값이 빈이 된다. */
    private class BeanMethodDefinition implements BeanDefinition {
        private final String configBeanName;
        private final Method method;

        BeanMethodDefinition(String configBeanName, Method method) {
            this.configBeanName = configBeanName;
            this.method = method;
        }

        @Override
        public Class<?> type() {
            return method.getReturnType();
        }

        @Override
        public Object create() throws IllegalAccessException, InvocationTargetException {
            Object configBean = genBean(configBeanName);
            Object[] args = resolveDependencies(method);

            method.setAccessible(true);
            return method.invoke(configBean, args);
        }
    }
}
