/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.tui.bindings.processor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

import dev.tamboui.tui.bindings.ActionHandler;
import dev.tamboui.tui.bindings.ActionHandlerRegistrar;
import dev.tamboui.tui.bindings.BindingSets;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseButton;
import dev.tamboui.tui.event.MouseEvent;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

class OnActionProcessorTest {

    /** Loads the classes of a compilation, so a test can run the generated code. */
    private static ClassLoader classLoaderFor(Compilation compilation) {
        return new ClassLoader(OnActionProcessorTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                Optional<JavaFileObject> file = compilation.generatedFile(
                        StandardLocation.CLASS_OUTPUT, name.replace('.', '/') + ".class");
                if (!file.isPresent()) {
                    throw new ClassNotFoundException(name);
                }
                try (InputStream in = file.get().openInputStream()) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        bytes.write(buffer, 0, read);
                    }
                    return defineClass(name, bytes.toByteArray(), 0, bytes.size());
                } catch (IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
        };
    }

    /** Returns the class a generated registrar registers handlers for. */
    private static Class<?> targetTypeOf(ClassLoader loader, String registrarClass) throws Exception {
        ActionHandlerRegistrar<?> registrar = (ActionHandlerRegistrar<?>) loader.loadClass(registrarClass)
                .getDeclaredConstructor().newInstance();
        return registrar.targetType();
    }

    @Test
    void generatesRegistrarForAnnotatedMethods() {
        JavaFileObject source = JavaFileObjects.forSourceString(
                "test.MyHandler",
                "package test;\n" +
                "\n" +
                "import dev.tamboui.annotations.bindings.OnAction;\n" +
                "import dev.tamboui.tui.event.Event;\n" +
                "\n" +
                "public class MyHandler {\n" +
                "    @OnAction(\"save\")\n" +
                "    void save(Event event) {}\n" +
                "\n" +
                "    @OnAction(\"quit\")\n" +
                "    void quit(Event event) {}\n" +
                "}\n"
        );

        Compilation compilation = javac()
                .withProcessors(new OnActionProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("test.MyHandler_ActionHandlerRegistrar")
                .contentsAsUtf8String()
                .contains("handler.on(\"save\", target::save)");
        assertThat(compilation)
                .generatedSourceFile("test.MyHandler_ActionHandlerRegistrar")
                .contentsAsUtf8String()
                .contains("handler.on(\"quit\", target::quit)");
        assertThat(compilation)
                .generatedSourceFile("test.MyHandler_ActionHandlerRegistrar")
                .contentsAsUtf8String()
                .contains("implements ActionHandlerRegistrar<MyHandler>");
    }

    @ParameterizedTest
    @ValueSource(strings = {"11", "17", "21"})
    void compilesWithoutWarningsAtNewerReleases(String release) {
        Compilation compilation = javac()
                .withProcessors(new OnActionProcessor())
                .withOptions("--release", release, "-Xlint:all", "-Werror")
                .compile(saveHandlerSource());

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(compilation)
                .generatedSourceFile("test.MyHandler_ActionHandlerRegistrar")
                .contentsAsUtf8String()
                .contains("handler.on(\"save\", target::save)");
    }

    @Test
    void compilesWithoutWarningsAtTheCompilersLatestSourceVersion() {
        // without --release, javac compiles at the latest source version it supports
        Compilation compilation = javac()
                .withProcessors(new OnActionProcessor())
                .withOptions("-Xlint:all", "-Werror")
                .compile(saveHandlerSource());

        assertThat(compilation).succeededWithoutWarnings();
    }

    private static JavaFileObject saveHandlerSource() {
        return JavaFileObjects.forSourceString(
                "test.MyHandler",
                "package test;\n" +
                "\n" +
                "import dev.tamboui.annotations.bindings.OnAction;\n" +
                "import dev.tamboui.tui.event.Event;\n" +
                "\n" +
                "public class MyHandler {\n" +
                "    @OnAction(\"save\")\n" +
                "    void save(Event event) {}\n" +
                "}\n"
        );
    }

    @Test
    void generatesServiceLoaderEntry() {
        JavaFileObject source = JavaFileObjects.forSourceString(
                "test.MyHandler",
                "package test;\n" +
                "\n" +
                "import dev.tamboui.annotations.bindings.OnAction;\n" +
                "import dev.tamboui.tui.event.Event;\n" +
                "\n" +
                "public class MyHandler {\n" +
                "    @OnAction(\"action\")\n" +
                "    void handle(Event event) {}\n" +
                "}\n"
        );

        Compilation compilation = javac()
                .withProcessors(new OnActionProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedFile(
                        StandardLocation.CLASS_OUTPUT,
                        "META-INF/services/dev.tamboui.tui.bindings.ActionHandlerRegistrar")
                .contentsAsUtf8String()
                .contains("test.MyHandler_ActionHandlerRegistrar");
    }

    @Test
    void handlesMultipleClassesInSameCompilation() {
        JavaFileObject source1 = JavaFileObjects.forSourceString(
                "test.Handler1",
                "package test;\n" +
                "\n" +
                "import dev.tamboui.annotations.bindings.OnAction;\n" +
                "import dev.tamboui.tui.event.Event;\n" +
                "\n" +
                "public class Handler1 {\n" +
                "    @OnAction(\"action1\")\n" +
                "    void handle(Event event) {}\n" +
                "}\n"
        );

        JavaFileObject source2 = JavaFileObjects.forSourceString(
                "test.Handler2",
                "package test;\n" +
                "\n" +
                "import dev.tamboui.annotations.bindings.OnAction;\n" +
                "import dev.tamboui.tui.event.Event;\n" +
                "\n" +
                "public class Handler2 {\n" +
                "    @OnAction(\"action2\")\n" +
                "    void handle(Event event) {}\n" +
                "}\n"
        );

        Compilation compilation = javac()
                .withProcessors(new OnActionProcessor())
                .compile(source1, source2);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("test.Handler1_ActionHandlerRegistrar");
        assertThat(compilation)
                .generatedSourceFile("test.Handler2_ActionHandlerRegistrar");
    }

    @Test
    void rejectsMethodWithNoParameters() {
        JavaFileObject source = JavaFileObjects.forSourceString(
                "test.MyHandler",
                "package test;\n" +
                "\n" +
                "import dev.tamboui.annotations.bindings.OnAction;\n" +
                "\n" +
                "public class MyHandler {\n" +
                "    @OnAction(\"action\")\n" +
                "    void handle() {}\n" +
                "}\n"
        );

        Compilation compilation = javac()
                .withProcessors(new OnActionProcessor())
                .compile(source);

        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining("@OnAction methods must have exactly one parameter of type Event");
    }

    @Test
    void rejectsMethodWithNonEventParameter() {
        JavaFileObject source = JavaFileObjects.forSourceString(
                "test.MyHandler",
                "package test;\n" +
                "\n" +
                "import dev.tamboui.annotations.bindings.OnAction;\n" +
                "\n" +
                "public class MyHandler {\n" +
                "    @OnAction(\"action\")\n" +
                "    void handle(int value) {}\n" +
                "}\n"
        );

        Compilation compilation = javac()
                .withProcessors(new OnActionProcessor())
                .compile(source);

        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining("@OnAction method parameter must be of type Event or a subtype");
    }

    @Test
    void usesConstantValueForActionName() {
        // When @OnAction uses a constant like Actions.QUIT, the processor
        // should resolve the string value
        JavaFileObject source = JavaFileObjects.forSourceString(
                "test.MyHandler",
                "package test;\n" +
                "\n" +
                "import dev.tamboui.annotations.bindings.OnAction;\n" +
                "import dev.tamboui.tui.bindings.Actions;\n" +
                "import dev.tamboui.tui.event.Event;\n" +
                "\n" +
                "public class MyHandler {\n" +
                "    @OnAction(Actions.QUIT)\n" +
                "    void quit(Event event) {}\n" +
                "}\n"
        );

        Compilation compilation = javac()
                .withProcessors(new OnActionProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("test.MyHandler_ActionHandlerRegistrar")
                .contentsAsUtf8String()
                .contains("handler.on(\"quit\", target::quit)");
    }

    @Test
    void generatesRegistrarsForNestedClasses() {
        JavaFileObject source = JavaFileObjects.forSourceString(
                "test.Outer",
                "package test;\n" +
                "\n" +
                "import dev.tamboui.annotations.bindings.OnAction;\n" +
                "import dev.tamboui.tui.event.Event;\n" +
                "\n" +
                "public class Outer {\n" +
                "    public static class Inner {\n" +
                "        @OnAction(\"save\")\n" +
                "        void save(Event event) {}\n" +
                "    }\n" +
                "\n" +
                "    static class Panel {\n" +
                "        class Inner {\n" +
                "            @OnAction(\"close\")\n" +
                "            void close(Event event) {}\n" +
                "        }\n" +
                "    }\n" +
                "}\n"
        );

        Compilation compilation = javac()
                .withProcessors(new OnActionProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("test.Outer_Inner_ActionHandlerRegistrar")
                .contentsAsUtf8String()
                .contains("implements ActionHandlerRegistrar<Outer.Inner>");
        assertThat(compilation)
                .generatedSourceFile("test.Outer_Panel_Inner_ActionHandlerRegistrar")
                .contentsAsUtf8String()
                .contains("return Outer.Panel.Inner.class;");
        assertThat(compilation)
                .generatedFile(
                        StandardLocation.CLASS_OUTPUT,
                        "META-INF/services/dev.tamboui.tui.bindings.ActionHandlerRegistrar")
                .contentsAsUtf8String()
                .contains("test.Outer_Panel_Inner_ActionHandlerRegistrar");
    }

    @Test
    void rejectsMethodInPrivateClass() {
        JavaFileObject source = JavaFileObjects.forSourceString(
                "test.Outer",
                "package test;\n" +
                "\n" +
                "import dev.tamboui.annotations.bindings.OnAction;\n" +
                "import dev.tamboui.tui.event.Event;\n" +
                "\n" +
                "public class Outer {\n" +
                "    private static class Hidden {\n" +
                "        @OnAction(\"action\")\n" +
                "        void handle(Event event) {}\n" +
                "    }\n" +
                "}\n"
        );

        Compilation compilation = javac()
                .withProcessors(new OnActionProcessor())
                .compile(source);

        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining("@OnAction methods must not be in a private class");
    }

    @Test
    void generatesRegistrarForEventSubtypeParameters() {
        JavaFileObject source = JavaFileObjects.forSourceString(
                "test.MyHandler",
                "package test;\n" +
                "\n" +
                "import dev.tamboui.annotations.bindings.OnAction;\n" +
                "import dev.tamboui.tui.event.ActionEvent;\n" +
                "import dev.tamboui.tui.event.KeyEvent;\n" +
                "import dev.tamboui.tui.event.MouseEvent;\n" +
                "\n" +
                "public class MyHandler {\n" +
                "    @OnAction(\"insert\")\n" +
                "    void insert(KeyEvent event) {}\n" +
                "\n" +
                "    @OnAction(\"click\")\n" +
                "    void click(MouseEvent event) {}\n" +
                "\n" +
                "    @OnAction(\"refresh\")\n" +
                "    void refresh(ActionEvent<Void> event) {}\n" +
                "}\n"
        );

        // with the lint options the TamboUI build uses
        Compilation compilation = javac()
                .withProcessors(new OnActionProcessor())
                .withOptions("--release", "8", "-Xlint:all", "-Xlint:-options", "-Werror")
                .compile(source);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("test.MyHandler_ActionHandlerRegistrar")
                .contentsAsUtf8String()
                .contains("if (event instanceof dev.tamboui.tui.event.KeyEvent) {");
    }

    @Test
    void generatedRegistrarCallsAMethodTakingAnEventSubtypeOnlyForEventsOfThatType() throws Exception {
        JavaFileObject source = JavaFileObjects.forSourceString(
                "test.MyHandler",
                "package test;\n" +
                "\n" +
                "import java.util.ArrayList;\n" +
                "import java.util.List;\n" +
                "\n" +
                "import dev.tamboui.annotations.bindings.OnAction;\n" +
                "import dev.tamboui.tui.event.KeyEvent;\n" +
                "\n" +
                "public class MyHandler {\n" +
                "    public final List<KeyEvent> received = new ArrayList<>();\n" +
                "\n" +
                "    @OnAction(\"insert\")\n" +
                "    void insert(KeyEvent event) {\n" +
                "        received.add(event);\n" +
                "    }\n" +
                "}\n"
        );
        Compilation compilation = javac()
                .withProcessors(new OnActionProcessor())
                .compile(source);
        assertThat(compilation).succeeded();

        ClassLoader loader = classLoaderFor(compilation);
        Object target = loader.loadClass("test.MyHandler").getDeclaredConstructor().newInstance();
        @SuppressWarnings("unchecked")
        ActionHandlerRegistrar<Object> registrar = (ActionHandlerRegistrar<Object>) loader
                .loadClass("test.MyHandler_ActionHandlerRegistrar").getDeclaredConstructor().newInstance();
        ActionHandler handler = new ActionHandler(BindingSets.standard());
        registrar.register(target, handler);
        KeyEvent key = KeyEvent.ofChar('x');

        handler.fire("insert", key);
        handler.fire("insert", MouseEvent.press(MouseButton.LEFT, 0, 0));
        handler.fire("insert");

        Object received = target.getClass().getField("received").get(target);
        Assertions.assertThat(received).isEqualTo(Collections.singletonList(key));
    }

    @Test
    void generatedRegistrarOfAGenericClassCompilesWithoutWarningsAndDispatchesActions() throws Exception {
        JavaFileObject source = JavaFileObjects.forSourceString(
                "test.MyHandler",
                "package test;\n" +
                "\n" +
                "import java.util.ArrayList;\n" +
                "import java.util.List;\n" +
                "\n" +
                "import dev.tamboui.annotations.bindings.OnAction;\n" +
                "import dev.tamboui.tui.event.Event;\n" +
                "import dev.tamboui.tui.event.KeyEvent;\n" +
                "\n" +
                "public class MyHandler<T, K extends KeyEvent> {\n" +
                "    public final List<Event> received = new ArrayList<>();\n" +
                "\n" +
                "    @OnAction(\"save\")\n" +
                "    void save(Event event) {\n" +
                "        received.add(event);\n" +
                "    }\n" +
                "\n" +
                "    @OnAction(\"insert\")\n" +
                "    void insert(K event) {\n" +
                "        received.add(event);\n" +
                "    }\n" +
                "}\n"
        );

        // with the lint options the TamboUI build uses
        Compilation compilation = javac()
                .withProcessors(new OnActionProcessor())
                .withOptions("--release", "8", "-Xlint:all", "-Xlint:-options", "-Werror")
                .compile(source);
        assertThat(compilation).succeeded();

        ClassLoader loader = classLoaderFor(compilation);
        Class<?> targetClass = loader.loadClass("test.MyHandler");
        Object target = targetClass.getDeclaredConstructor().newInstance();
        @SuppressWarnings("unchecked")
        ActionHandlerRegistrar<Object> registrar = (ActionHandlerRegistrar<Object>) loader
                .loadClass("test.MyHandler_ActionHandlerRegistrar").getDeclaredConstructor().newInstance();
        // ActionHandler.registerAnnotated uses the registrar whose target type is the target's class
        Assertions.assertThat(registrar.targetType()).isSameAs(targetClass);
        ActionHandler handler = new ActionHandler(BindingSets.standard());
        registrar.register(target, handler);
        KeyEvent key = KeyEvent.ofChar('x');
        MouseEvent click = MouseEvent.press(MouseButton.LEFT, 0, 0);

        handler.fire("save", click);
        handler.fire("insert", key);
        handler.fire("insert", click);

        Object received = targetClass.getField("received").get(target);
        Assertions.assertThat(received).isEqualTo(Arrays.asList(click, key));
    }

    @Test
    void generatesRegistrarsWithoutWarningsForNestedClassesOfGenericClasses() throws Exception {
        JavaFileObject source = JavaFileObjects.forSourceString(
                "test.Outer",
                "package test;\n" +
                "\n" +
                "import dev.tamboui.annotations.bindings.OnAction;\n" +
                "import dev.tamboui.tui.event.Event;\n" +
                "\n" +
                "public class Outer<T> {\n" +
                "    public static class Nested<U> {\n" +
                "        @OnAction(\"save\")\n" +
                "        void save(Event event) {}\n" +
                "    }\n" +
                "\n" +
                "    public class Inner {\n" +
                "        @OnAction(\"close\")\n" +
                "        void close(Event event) {}\n" +
                "    }\n" +
                "\n" +
                "    public static class Plain {\n" +
                "        @OnAction(\"quit\")\n" +
                "        void quit(Event event) {}\n" +
                "    }\n" +
                "}\n"
        );

        Compilation compilation = javac()
                .withProcessors(new OnActionProcessor())
                .withOptions("--release", "8", "-Xlint:all", "-Xlint:-options", "-Werror")
                .compile(source);

        assertThat(compilation).succeeded();
        ClassLoader loader = classLoaderFor(compilation);
        Assertions.assertThat(targetTypeOf(loader, "test.Outer_Nested_ActionHandlerRegistrar"))
                .isSameAs(loader.loadClass("test.Outer$Nested"));
        Assertions.assertThat(targetTypeOf(loader, "test.Outer_Inner_ActionHandlerRegistrar"))
                .isSameAs(loader.loadClass("test.Outer$Inner"));
        // a static class in a generic class is not generic itself: nothing to suppress
        assertThat(compilation)
                .generatedSourceFile("test.Outer_Plain_ActionHandlerRegistrar")
                .contentsAsUtf8String()
                .doesNotContain("@SuppressWarnings");
    }
}
