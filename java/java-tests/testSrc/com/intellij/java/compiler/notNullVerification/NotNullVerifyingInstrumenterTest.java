// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.java.compiler.notNullVerification;

import com.intellij.JavaTestUtil;
import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.compiler.instrumentation.FailSafeClassReader;
import com.intellij.compiler.notNullVerification.NotNullVerifyingInstrumenter;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.project.IntelliJProjectConfiguration;
import com.intellij.testFramework.IdeaTestUtil;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.rules.TempDirectory;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ExceptionUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.org.objectweb.asm.ClassWriter;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static com.intellij.testFramework.UsefulTestCase.assertInstanceOf;
import static org.junit.Assert.*;

/**
 * @author yole
 */
public class NotNullVerifyingInstrumenterTest {
  @Rule public TempDirectory tempDir = new TempDirectory();
  @Rule public TestName testName = new TestName();

  @Test
  public void testSimpleReturn() throws Exception {
    Class<?> testClass = prepareTest();
    Object instance = testClass.newInstance();
    Method method = testClass.getMethod("test");
    verifyCallThrowsException("@NotNull method SimpleReturn.test must not return null", instance, method);
  }

  @Test
  public void testSimpleReturnWithMessage() throws Exception {
    Class<?> testClass = prepareTest();
    Object instance = testClass.newInstance();
    Method method = testClass.getMethod("test");
    verifyCallThrowsException("This method cannot return null", instance, method);
  }

  @Test
  public void testMultipleReturns() throws Exception {
    Class<?> testClass = prepareTest();
    Object instance = testClass.newInstance();
    Method method = testClass.getMethod("test", int.class);
    verifyCallThrowsException("@NotNull method MultipleReturns.test must not return null", instance, method, 1);
  }

  @Test
  public void testSimpleParam() throws Exception {
    Class<?> testClass = prepareTest();
    Object instance = testClass.newInstance();
    Method method = testClass.getMethod("test", Object.class);
    verifyCallThrowsException("Argument 0 for @NotNull parameter of SimpleParam.test must not be null", instance, method, (Object)null);
  }

  @Test
  public void testSimpleParamWithMessage() throws Exception {
    Class<?> testClass = prepareTest();
    Object instance = testClass.newInstance();
    Method method = testClass.getMethod("test", Object.class);
    verifyCallThrowsException("SimpleParamWithMessage.test(o) cant be null", instance, method, (Object)null);
  }

  @Test
  public void testConstructorParam() throws Exception {
    Class<?> testClass = prepareTest();
    Constructor method = testClass.getConstructor(Object.class);
    verifyCallThrowsException("Argument 0 for @NotNull parameter of ConstructorParam.<init> must not be null", null, method, (Object)null);
  }

  @Test
  public void testConstructorParamWithMessage() throws Exception {
    Class<?> testClass = prepareTest();
    Constructor method = testClass.getConstructor(Object.class);
    verifyCallThrowsException("ConstructorParam.ConstructorParam.o cant be null", null, method, (Object)null);
  }

  @Test
  public void testUseParameterNames() throws Exception {
    Class<?> testClass = prepareTest(true, AnnotationUtil.NOT_NULL);
    Constructor constructor = testClass.getConstructor(Object.class, Object.class);
    verifyCallThrowsException("Argument for @NotNull parameter 'obj2' of UseParameterNames.<init> must not be null", null, constructor, null, null);

    Method staticMethod = testClass.getMethod("staticMethod", Object.class);
    verifyCallThrowsException("Argument for @NotNull parameter 'y' of UseParameterNames.staticMethod must not be null", null, staticMethod, (Object)null);

    Object instance = constructor.newInstance("", "");
    Method instanceMethod = testClass.getMethod("instanceMethod", Object.class);
    verifyCallThrowsException("Argument for @NotNull parameter 'x' of UseParameterNames.instanceMethod must not be null", instance, instanceMethod, (Object)null);
  }

  @Test
  public void testLongParameter() throws Exception {
    Class<?> testClass = prepareTest(true, AnnotationUtil.NOT_NULL);
    Method staticMethod = testClass.getMethod("foo", long.class, String.class, String.class);
    verifyCallThrowsException("Argument for @NotNull parameter 'c' of LongParameter.foo must not be null", null, staticMethod, new Long(2), "z", null);
  }

  @Test
  public void testDoubleParameter() throws Exception {
    Class<?> testClass = prepareTest(true, AnnotationUtil.NOT_NULL);
    Method staticMethod = testClass.getMethod("foo", double.class, String.class, String.class);
    verifyCallThrowsException("Argument for @NotNull parameter 'c' of DoubleParameter.foo must not be null", null, staticMethod, new Long(2), "z", null);
  }

  @Test
  public void testEnumConstructor() throws Exception {
    Class testClass = prepareTest();
    assertNotNull(testClass.getField("Value").get(null));
  }

  @Test
  public void testCustomExceptionType() throws Exception {
    Class<?> testClass = prepareTest();
    try {
      testClass.getMethod("foo", Object.class, Object.class).invoke(testClass.newInstance(), null, null);
      fail();
    }
    catch (InvocationTargetException e) {
      assertInstanceOf(e.getCause(), NullPointerException.class);
      assertEquals("Argument 1 for @NotNull parameter of CustomExceptionType.foo must not be null", e.getCause().getMessage());
    }
  }

  @Test
  public void testEnumConstructorSecondParam() throws Exception {
    Class testClass = prepareTest();
    assertNotNull(testClass.getField("Value").get(null));
  }

  @Test
  public void testGroovyEnum() throws Exception {
    Class testClass = prepareTest();
    assertNotNull(testClass.getField("Value").get(null));
  }

  @Test
  public void testStaticInnerClass() throws Exception {
    Class aClass = prepareTest();
    assertNotNull(aClass.newInstance());
  }

  @Test
  public void testNonStaticInnerClass() throws Exception {
    Class aClass = prepareTest();
    assertNotNull(aClass.newInstance());
  }

  @Test
  public void testGroovyInnerClass() throws Exception {
    Class aClass = prepareTest();
    assertNotNull(aClass.newInstance());
  }

  @Test
  public void testSkipBridgeMethods() throws Exception {
    Class<?> testClass = prepareTest();
    try {
      testClass.getMethod("main").invoke(null);
      fail();
    }
    catch (InvocationTargetException e) {
      assertInstanceOf(e.getCause(), IllegalArgumentException.class);
      String trace = ExceptionUtil.getThrowableText(e.getCause());
      assertEquals("Exception should happen in real, non-bridge method: " + trace,
                   2, StringUtil.getOccurrenceCount(trace, "B.getObject(SkipBridgeMethods"));
    }
  }

  @Test
  public void testMultipleMessages() throws Exception {
    Class<?> test = prepareTest();
    Object instance = test.newInstance();
    verifyCallThrowsException("Argument 0 for @NotNull parameter of MultipleMessages.bar1 must not be null", instance, test.getMethod("bar1", Object.class),
                              (Object)null);
    verifyCallThrowsException("Argument 0 for @NotNull parameter of MultipleMessages.bar2 must not be null", instance, test.getMethod("bar2", Object.class),
                              (Object)null);
    verifyCallThrowsException("@NotNull method MultipleMessages.foo1 must not return null", instance, test.getMethod("foo1"));
    verifyCallThrowsException("@NotNull method MultipleMessages.foo2 must not return null", instance, test.getMethod("foo2"));
  }

  @Test
  public void testMultipleAnnotations() throws Exception {
    Class<?> test = prepareTest(false, "FooAnno", "BarAnno");
    Object instance = test.newInstance();
    verifyCallThrowsException("@FooAnno method MultipleAnnotations.foo1 must not return null", instance, test.getMethod("foo1"));
    verifyCallThrowsException("@BarAnno method MultipleAnnotations.foo2 must not return null", instance, test.getMethod("foo2"));
  }

  @Test
  public void testTypeUseOnlyAnnotations() throws Exception {
    Class<?> test = prepareTest(false, "FooAnno");
    Object instance = test.newInstance();
    verifyCallThrowsException("@FooAnno method TypeUseOnlyAnnotations.foo1 must not return null", instance, test.getMethod("foo1"));
    verifyCallThrowsException("Argument 0 for @FooAnno parameter of TypeUseOnlyAnnotations.foo2 must not be null", instance, test.getMethod("foo2", String.class), (String)null);
    test.getMethod("foo3", List.class).invoke(instance, new Object[]{null});
  }

  @Test
  public void testTypeUseInEnumConstructor() throws Exception {
    Class<?> test = prepareTest(false, "TypeUseNotNull");
    assertEquals(1, test.getEnumConstants().length);
  }

  @Test
  public void testTypeUseAndMemberAnnotations() throws Exception {
    Class<?> test = prepareTest(false, "FooAnno");
    Object instance = test.newInstance();
    verifyCallThrowsException("@FooAnno method TypeUseAndMemberAnnotations.foo1 must not return null", instance, test.getMethod("foo1"));
    verifyCallThrowsException("Argument 0 for @FooAnno parameter of TypeUseAndMemberAnnotations.foo2 must not be null", instance, test.getMethod("foo2", String.class), (String)null);

    Method returnType = test.getMethod("returnType");
    verifyCallThrowsException("@FooAnno method TypeUseAndMemberAnnotations.returnType must not return null", instance, returnType);

    assertEquals(1, returnType.getAnnotations().length);
    assertEquals(1, returnType.getAnnotatedReturnType().getAnnotations().length);
  }

  @Test
  public void testMalformedBytecode() throws Exception {
    Class<?> testClass = prepareTest(false, AnnotationUtil.NOT_NULL);
    verifyCallThrowsException("Argument 0 for @NotNull parameter of MalformedBytecode$NullTest2.handle must not be null", null, testClass.getMethod("main"));
  }

  @Test
  public void testEnclosingClass() throws Exception {
    Class<?> testClass = prepareTest();
    Object obj1 = testClass.getMethod("fromStatic").invoke(null);
    assertEquals(testClass, obj1.getClass().getEnclosingClass());
    Object obj2 = testClass.getMethod("fromInstance").invoke(testClass.newInstance());
    assertEquals(testClass, obj2.getClass().getEnclosingClass());
  }

  @Test
  public void testLocalClassImplicitParameters() throws Exception {
    Class<?> test = prepareTest(true, "NotNull");
    Object instance = test.newInstance();
    assertEquals(42, test.getMethod("ok").invoke(instance));
    verifyCallThrowsException("Argument for @NotNull parameter 'test' of LocalClassImplicitParameters$1Test.<init> must not be null", instance, test.getMethod("failLocal"));
    verifyCallThrowsException("Argument for @NotNull parameter 'test' of LocalClassImplicitParameters$1Test2.<init> must not be null", instance, test.getMethod("failLocal2NotNull"));
    verifyCallThrowsException("Argument for @NotNull parameter 'another' of LocalClassImplicitParameters$1Test3.<init> must not be null", instance, test.getMethod("failLocalNullableNotNull"));
    verifyCallThrowsException("Argument for @NotNull parameter 'test' of LocalClassImplicitParameters$1.method must not be null", instance, test.getMethod("failAnonymous"));
    verifyCallThrowsException("Argument for @NotNull parameter 'param' of LocalClassImplicitParameters$Inner.<init> must not be null", instance, test.getMethod("failInner"));
  }

  private static void verifyCallThrowsException(String expectedError, @Nullable Object instance, Member member, Object... args) throws Exception {
    String exceptionText = null;
    try {
      if (member instanceof Constructor) {
        ((Constructor)member).newInstance(args);
      }
      else {
        ((Method)member).invoke(instance, args);
      }
    }
    catch (InvocationTargetException ex) {
      Throwable cause = ex.getCause();
      if (cause instanceof IllegalStateException || cause instanceof IllegalArgumentException) {
        exceptionText = cause.getMessage();
      }
    }
    assertEquals(expectedError, exceptionText);
  }

  private Class<?> prepareTest() throws IOException {
    return prepareTest(false, AnnotationUtil.NOT_NULL);
  }

  private Class<?> prepareTest(boolean withDebugInfo, String... notNullAnnotations) throws IOException {
    String testDir = JavaTestUtil.getJavaTestDataPath() + "/compiler/notNullVerification/";
    String testName = PlatformTestUtil.getTestName(this.testName.getMethodName(), false);
    File testFile = IdeaTestUtil.findSourceFile(testDir + testName);
    File classesDir = tempDir.newFolder("output");
    List<String> libRoots = IntelliJProjectConfiguration.getProjectLibraryClassesRootPaths("jetbrains-annotations-java5");
    List<String> args = ContainerUtil.newArrayList("-cp", StringUtil.join(libRoots, File.pathSeparator));
    if (withDebugInfo) args.add("-g");
    IdeaTestUtil.compileFile(testFile, classesDir, ArrayUtil.toStringArray(args));

    File[] files = classesDir.listFiles();
    assertNotNull(files);
    Arrays.sort(files, (o1, o2) -> o1.getName().compareToIgnoreCase(o2.getName()));
    boolean modified = false;
    MyClassLoader classLoader = new MyClassLoader(getClass().getClassLoader());
    Class mainClass = null;
    for (File file: files) {
      FailSafeClassReader reader = new FailSafeClassReader(FileUtil.loadFileBytes(file));
      ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
      modified |= NotNullVerifyingInstrumenter.processClassFile(reader, writer, notNullAnnotations);
      String className = FileUtil.getNameWithoutExtension(file.getName());
      Class aClass = classLoader.doDefineClass(className, writer.toByteArray());
      if (className.equals(testName)) {
        mainClass = aClass;
      }
    }
    assertTrue("Class file not instrumented!", modified);
    assertNotNull("Class " + testName + " not found!", mainClass);
    return mainClass;
  }

  private static class MyClassLoader extends ClassLoader {
    MyClassLoader(ClassLoader parent) {
      super(parent);
    }

    public Class doDefineClass(String name, byte[] data) {
      return defineClass(name, data, 0, data.length);
    }
  }
}