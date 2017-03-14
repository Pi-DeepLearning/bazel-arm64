// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.android.resources;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import com.android.builder.internal.SymbolLoader;
import com.android.utils.ILogger;
import com.android.utils.StdLogger;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Tests for {@link RClassGenerator}.
 */
@RunWith(JUnit4.class)
public class RClassGeneratorTest {

  private Path temp;
  private ILogger stdLogger;
  @Rule
  public final ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() throws Exception {
    temp = Files.createTempDirectory(toString());
    stdLogger = new StdLogger(StdLogger.Level.VERBOSE);
  }

  @Test
  public void plainInts() throws Exception {
    checkSimpleInts(true);
  }

  @Test
  public void nonFinalFields() throws Exception {
    checkSimpleInts(false);
  }

  private void checkSimpleInts(boolean finalFields) throws Exception {
    // R.txt with the real IDs after linking together libraries.
    SymbolLoader symbolValues = createSymbolFile("R.txt",
        "int attr agility 0x7f010000",
        "int attr dexterity 0x7f010001",
        "int drawable heart 0x7f020000",
        "int id someTextView 0x7f080000",
        "int integer maxNotifications 0x7f090000",
        "int string alphabet 0x7f100000",
        "int string ok 0x7f100001");
    // R.txt for the library, where the values are not the final ones (so ignore them). We only use
    // this to keep the # of inner classes small (exactly the set needed by the library).
    SymbolLoader symbolsInLibrary = createSymbolFile("lib.R.txt",
        "int attr agility 0x1",
        "int id someTextView 0x1",
        "int string ok 0x1");
    Path out = temp.resolve("classes");
    Files.createDirectories(out);
    RClassGenerator writer = RClassGenerator.fromSymbols(
        out, "com.bar", symbolValues, ImmutableList.of(symbolsInLibrary), finalFields);
    writer.write();

    Path packageDir = out.resolve("com/bar");
    checkFilesInPackage(packageDir, "R.class", "R$attr.class", "R$id.class", "R$string.class");
    Class<?> outerClass = checkTopLevelClass(out,
        "com.bar.R",
        "com.bar.R$attr",
        "com.bar.R$id",
        "com.bar.R$string");
    checkInnerClass(out,
        "com.bar.R$attr",
        outerClass,
        ImmutableMap.of("agility", 0x7f010000),
        ImmutableMap.<String, List<Integer>>of(),
        finalFields
    );
    checkInnerClass(out,
        "com.bar.R$id",
        outerClass,
        ImmutableMap.of("someTextView", 0x7f080000),
        ImmutableMap.<String, List<Integer>>of(),
        finalFields
    );
    checkInnerClass(out,
        "com.bar.R$string",
        outerClass,
        ImmutableMap.of("ok", 0x7f100001),
        ImmutableMap.<String, List<Integer>>of(),
        finalFields
    );
  }

  @Test
  public void emptyIntArrays() throws Exception {
    boolean finalFields = true;
    // Make sure we parse an empty array the way the R.txt writes it.
    SymbolLoader symbolValues = createSymbolFile("R.txt",
        "int[] styleable ActionMenuView { }");
    SymbolLoader symbolsInLibrary = symbolValues;
    Path out = temp.resolve("classes");
    Files.createDirectories(out);
    RClassGenerator writer = RClassGenerator.fromSymbols(out, "com.testEmptyIntArray",
        symbolValues, ImmutableList.of(symbolsInLibrary), finalFields);
    writer.write();

    Path packageDir = out.resolve("com/testEmptyIntArray");
    checkFilesInPackage(packageDir, "R.class", "R$styleable.class");
    Class<?> outerClass = checkTopLevelClass(out,
        "com.testEmptyIntArray.R",
        "com.testEmptyIntArray.R$styleable");
    checkInnerClass(out,
        "com.testEmptyIntArray.R$styleable",
        outerClass,
        ImmutableMap.<String, Integer>of(),
        ImmutableMap.<String, List<Integer>>of(
            "ActionMenuView", ImmutableList.<Integer>of()
        ),
        finalFields
    );
  }

  @Test
  public void corruptIntArraysTrailingComma() throws Exception {
    boolean finalFields = true;
    // Test a few cases of what happens if the R.txt is corrupted. It shouldn't happen unless there
    // is a bug in aapt, or R.txt is manually written the wrong way.
    SymbolLoader symbolValues = createSymbolFile("R.txt",
        "int[] styleable ActionMenuView { 1, }");
    SymbolLoader symbolsInLibrary = symbolValues;
    Path out = temp.resolve("classes");
    Files.createDirectories(out);
    thrown.expect(NumberFormatException.class);
    RClassGenerator writer = RClassGenerator.fromSymbols(out, "com.foo",
        symbolValues, ImmutableList.of(symbolsInLibrary), finalFields);
    writer.write();
  }

  @Test
  public void corruptIntArraysOmittedMiddle() throws Exception {
    boolean finalFields = true;
    SymbolLoader symbolValues = createSymbolFile("R.txt",
        "int[] styleable ActionMenuView { 1, , 2 }");
    SymbolLoader symbolsInLibrary = symbolValues;
    Path out = temp.resolve("classes");
    Files.createDirectories(out);
    thrown.expect(NumberFormatException.class);
    RClassGenerator writer = RClassGenerator.fromSymbols(out, "com.foo",
        symbolValues, ImmutableList.of(symbolsInLibrary), finalFields);
    writer.write();
  }

  @Test
  public void binaryDropsLibraryFields() throws Exception {
    boolean finalFields = true;
    // Test what happens if the binary R.txt is not a strict superset of the
    // library R.txt (overrides that drop elements).
    SymbolLoader symbolValues = createSymbolFile("R.txt",
        "int layout stubbable_activity 0x7f020000");
    SymbolLoader symbolsInLibrary = createSymbolFile("lib.R.txt",
        "int id debug_text_field 0x1",
        "int id debug_text_field2 0x1",
        "int layout stubbable_activity 0x1");
    Path out = temp.resolve("classes");
    Files.createDirectories(out);
    RClassGenerator writer = RClassGenerator.fromSymbols(out, "com.foo",
        symbolValues, ImmutableList.of(symbolsInLibrary), finalFields);
    writer.write();

    Path packageDir = out.resolve("com/foo");
    checkFilesInPackage(packageDir, "R.class", "R$id.class", "R$layout.class");
    Class<?> outerClass = checkTopLevelClass(out,
        "com.foo.R",
        "com.foo.R$id",
        "com.foo.R$layout");
    checkInnerClass(out,
        "com.foo.R$id",
        outerClass,
        ImmutableMap.<String, Integer>of(),
        ImmutableMap.<String, List<Integer>>of(),
        finalFields
    );
    checkInnerClass(out,
        "com.foo.R$layout",
        outerClass,
        ImmutableMap.of("stubbable_activity", 0x7f020000),
        ImmutableMap.<String, List<Integer>>of(),
        finalFields
    );
  }

  @Test
  public void intArraysFinal() throws Exception {
    checkIntArrays(true);
  }

  @Test
  public void intArraysNonFinal() throws Exception {
    checkIntArrays(false);
  }

  public void checkIntArrays(boolean finalFields) throws Exception {
    SymbolLoader symbolValues = createSymbolFile("R.txt",
        "int attr android_layout 0x010100f2",
        "int attr bar 0x7f010001",
        "int attr baz 0x7f010002",
        "int attr fox 0x7f010003",
        "int attr attr 0x7f010004",
        "int attr another_attr 0x7f010005",
        "int attr zoo 0x7f010006",
        // Test several > 5 elements, so that clinit must use bytecodes other than iconst_0 to 5.
        "int[] styleable ActionButton { 0x010100f2, 0x7f010001, 0x7f010002, 0x7f010003, "
            + "0x7f010004, 0x7f010005, 0x7f010006 }",
        // The array indices of each attribute.
        "int styleable ActionButton_android_layout 0",
        "int styleable ActionButton_another_attr 5",
        "int styleable ActionButton_attr 4",
        "int styleable ActionButton_bar 1",
        "int styleable ActionButton_baz 2",
        "int styleable ActionButton_fox 3",
        "int styleable ActionButton_zoo 6"
    );
    SymbolLoader symbolsInLibrary = symbolValues;
    Path out = temp.resolve("classes");
    Files.createDirectories(out);
    RClassGenerator writer = RClassGenerator.fromSymbols(
        out, "com.intArray", symbolValues, ImmutableList.of(symbolsInLibrary), finalFields);
    writer.write();

    Path packageDir = out.resolve("com/intArray");
    checkFilesInPackage(packageDir, "R.class", "R$attr.class", "R$styleable.class");
    Class<?> outerClass = checkTopLevelClass(out,
        "com.intArray.R",
        "com.intArray.R$attr",
        "com.intArray.R$styleable");
    checkInnerClass(out,
        "com.intArray.R$attr",
        outerClass,
        ImmutableMap.<String, Integer>builder()
            .put("android_layout", 0x010100f2)
            .put("bar", 0x7f010001)
            .put("baz", 0x7f010002)
            .put("fox", 0x7f010003)
            .put("attr", 0x7f010004)
            .put("another_attr", 0x7f010005)
            .put("zoo", 0x7f010006)
            .build(),
        ImmutableMap.<String, List<Integer>>of(),
        finalFields
    );
    checkInnerClass(out,
        "com.intArray.R$styleable",
        outerClass,
        ImmutableMap.<String, Integer>builder()
            .put("ActionButton_android_layout", 0)
            .put("ActionButton_bar", 1)
            .put("ActionButton_baz", 2)
            .put("ActionButton_fox", 3)
            .put("ActionButton_attr", 4)
            .put("ActionButton_another_attr", 5)
            .put("ActionButton_zoo", 6)
            .build(),
        ImmutableMap.<String, List<Integer>>of(
            "ActionButton",
            ImmutableList.of(0x010100f2, 0x7f010001, 0x7f010002,
                0x7f010003, 0x7f010004, 0x7f010005, 0x7f010006)
        ),
        finalFields
    );
  }

  @Test
  public void emptyPackage() throws Exception {
    boolean finalFields = true;
    // Make sure we handle an empty package string.
    SymbolLoader symbolValues = createSymbolFile("R.txt", "int string some_string 0x7f200000");
    SymbolLoader symbolsInLibrary = symbolValues;
    Path out = temp.resolve("classes");
    Files.createDirectories(out);
    RClassGenerator writer =
        RClassGenerator.fromSymbols(
            out, "", symbolValues, ImmutableList.of(symbolsInLibrary), finalFields);
    writer.write();

    Path packageDir = out.resolve("");
    checkFilesInPackage(packageDir, "R.class", "R$string.class");
    Class<?> outerClass = checkTopLevelClass(out, "R", "R$string");
    checkInnerClass(
        out,
        "R$string",
        outerClass,
        ImmutableMap.of("some_string", 0x7f200000),
        ImmutableMap.<String, List<Integer>>of(),
        finalFields);
  }

  // Test utilities

  private Path createFile(String name, String... contents) throws IOException {
    Path path = temp.resolve(name);
    Files.createDirectories(path.getParent());
    Files.newOutputStream(path).write(
        Joiner.on("\n").join(contents).getBytes(StandardCharsets.UTF_8));
    return path;
  }

  private SymbolLoader createSymbolFile(String name, String... contents) throws IOException {
    Path path = createFile(name, contents);
    SymbolLoader symbolFile = new SymbolLoader(path.toFile(), stdLogger);
    symbolFile.load();
    return symbolFile;
  }

  private static void checkFilesInPackage(Path packageDir, String... expectedFiles)
      throws IOException {
    ImmutableList<String> filesInPackage = ImmutableList
        .copyOf(Iterables.transform(Files.newDirectoryStream(packageDir),
            new Function<Path, String>() {
              @Override
              public String apply(Path path) {
                return path.getFileName().toString();
              }
            }
        ));
    assertThat(filesInPackage).containsExactly((Object[]) expectedFiles);
  }

  private static Class<?> checkTopLevelClass(
      Path baseDir,
      String expectedClassName,
      String... expectedInnerClasses)
      throws Exception {
    URLClassLoader urlClassLoader = new URLClassLoader(new URL[]{baseDir.toUri().toURL()});
    Class<?> toplevelClass = urlClassLoader.loadClass(expectedClassName);
    assertThat(toplevelClass.getSuperclass()).isEqualTo(Object.class);
    int outerModifiers = toplevelClass.getModifiers();
    assertThat(Modifier.isFinal(outerModifiers)).isTrue();
    assertThat(Modifier.isPublic(outerModifiers)).isTrue();
    ImmutableList.Builder<String> actualClasses = ImmutableList.builder();
    for (Class<?> innerClass : toplevelClass.getClasses()) {
      assertThat(innerClass.getDeclaredClasses()).isEmpty();
      int modifiers = innerClass.getModifiers();
      assertThat(Modifier.isFinal(modifiers)).isTrue();
      assertThat(Modifier.isPublic(modifiers)).isTrue();
      assertThat(Modifier.isStatic(modifiers)).isTrue();
      actualClasses.add(innerClass.getName());
    }
    assertThat(actualClasses.build()).containsExactly((Object[]) expectedInnerClasses);
    return toplevelClass;
  }

  private void checkInnerClass(
      Path baseDir,
      String expectedClassName,
      Class<?> outerClass,
      ImmutableMap<String, Integer> intFields,
      ImmutableMap<String, List<Integer>> intArrayFields,
      boolean areFieldsFinal) throws Exception {
    URLClassLoader urlClassLoader = new URLClassLoader(new URL[]{baseDir.toUri().toURL()});
    Class<?> innerClass = urlClassLoader.loadClass(expectedClassName);
    assertThat(innerClass.getSuperclass()).isEqualTo(Object.class);
    assertThat(innerClass.getEnclosingClass().toString())
        .isEqualTo(outerClass.toString());
    ImmutableMap.Builder<String, Integer> actualIntFields = ImmutableMap.builder();
    ImmutableMap.Builder<String, List<Integer>> actualIntArrayFields = ImmutableMap.builder();
    for (Field f : innerClass.getFields()) {
      int fieldModifiers = f.getModifiers();
      assertThat(Modifier.isFinal(fieldModifiers)).isEqualTo(areFieldsFinal);
      assertThat(Modifier.isPublic(fieldModifiers)).isTrue();
      assertThat(Modifier.isStatic(fieldModifiers)).isTrue();

      Class<?> fieldType = f.getType();
      if (fieldType.isPrimitive()) {
        assertThat(fieldType).isEqualTo(Integer.TYPE);
        actualIntFields.put(f.getName(), (Integer) f.get(null));
      } else {
        assertThat(fieldType.isArray()).isTrue();
        int[] asArray = (int[]) f.get(null);
        ImmutableList.Builder<Integer> list = ImmutableList.builder();
        for (int i : asArray) {
          list.add(i);
        }
        actualIntArrayFields.put(f.getName(), list.build());
      }
    }
    assertThat(actualIntFields.build()).containsExactlyEntriesIn(intFields);
    assertThat(actualIntArrayFields.build()).containsExactlyEntriesIn(intArrayFields);
  }

}
