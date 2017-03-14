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
package com.google.devtools.build.android;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link RClassGeneratorAction}.
 */
@RunWith(JUnit4.class)
public class RClassGeneratorActionTest {

  private Path tempDir;

  @Before
  public void setUp() throws IOException {
    tempDir = Files.createTempDirectory(toString());
  }

  /**
   * TODO(jvoung): use {@link AndroidDataBuilder} instead, once that's moved to this source tree.
   * This is a slimmed down version used to avoid dependencies.
   */
  private static class ManifestBuilder {

    private final Path root;

    private ManifestBuilder(Path root) {
      this.root = root;
    }

    public static ManifestBuilder of(Path root) {
      return new ManifestBuilder(root);
    }

    public Path createManifest(String path, String manifestPackage, String... lines)
        throws IOException {
      Path manifest = root.resolve(path);
      Files.createDirectories(root);
      Files.write(manifest,
          String.format(
              "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                  + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" "
                  + " package=\"%s\">"
                  + "%s</manifest>",
              manifestPackage,
              Joiner.on("\n").join(lines)).getBytes(StandardCharsets.UTF_8));
      return manifest;
    }
  }

  @Test
  public void withBinaryAndLibraries() throws Exception {
    Path binaryManifest = ManifestBuilder.of(tempDir.resolve("binary"))
        .createManifest("AndroidManifest.xml", "com.google.app",
            "<application android:name=\"com.google.app\">",
            "<activity android:name=\"com.google.bar.activityFoo\" />",
            "</application>");
    Path libFooManifest = ManifestBuilder.of(tempDir.resolve("libFoo"))
        .createManifest("AndroidManifest.xml", "com.google.foo", "");
    Path libBarManifest = ManifestBuilder.of(tempDir.resolve("libBar"))
        .createManifest("AndroidManifest.xml", "com.google.bar", "");

    Path binarySymbols = createFile("R.txt",
        "int attr agility 0x7f010000",
        "int attr dexterity 0x7f010001",
        "int drawable heart 0x7f020000",
        "int id someTextView 0x7f080000",
        "int integer maxNotifications 0x7f090000",
        "int string alphabet 0x7f100000",
        "int string ok 0x7f100001");
    Path libFooSymbols = createFile("libFoo.R.txt",
        "int attr agility 0x1",
        "int id someTextView 0x1",
        "int string ok 0x1");
    Path libBarSymbols = createFile("libBar.R.txt",
        "int attr dexterity 0x1",
        "int drawable heart 0x1");

    Path jarPath = tempDir.resolve("app_resources.jar");

    RClassGeneratorAction.main(
        ImmutableList.<String>of(
            "--primaryRTxt",
            binarySymbols.toString(),
            "--primaryManifest",
            binaryManifest.toString(),
            "--libraries",
            libFooSymbols
                + File.pathSeparator
                + libFooManifest
                + ","
                + libBarSymbols
                + File.pathSeparator
                + libBarManifest,
            "--classJarOutput",
            jarPath.toString())
            .toArray(new String[0]));

    assertThat(Files.exists(jarPath)).isTrue();
    assertThat(Files.getLastModifiedTime(jarPath)).isEqualTo(FileTime.fromMillis(0));

    try (ZipFile zip = new ZipFile(jarPath.toFile())) {
      List<? extends ZipEntry> zipEntries = Collections.list(zip.entries());
      Iterable<String> entries = getZipFilenames(zipEntries);
      assertThat(entries)
          .containsExactly(
              Paths.get("com/google/foo/R$attr.class").toString(),
              Paths.get("com/google/foo/R$id.class").toString(),
              Paths.get("com/google/foo/R$string.class").toString(),
              Paths.get("com/google/foo/R.class").toString(),
              Paths.get("com/google/bar/R$attr.class").toString(),
              Paths.get("com/google/bar/R$drawable.class").toString(),
              Paths.get("com/google/bar/R.class").toString(),
              Paths.get("com/google/app/R$attr.class").toString(),
              Paths.get("com/google/app/R$drawable.class").toString(),
              Paths.get("com/google/app/R$id.class").toString(),
              Paths.get("com/google/app/R$integer.class").toString(),
              Paths.get("com/google/app/R$string.class").toString(),
              Paths.get("com/google/app/R.class").toString(),
              Paths.get("META-INF/MANIFEST.MF").toString());
    }
  }

  @Test
  public void withBinaryNoLibraries() throws Exception {
    Path binaryManifest = ManifestBuilder.of(tempDir.resolve("binary"))
        .createManifest("AndroidManifest.xml", "com.google.app",
            "<application android:name=\"com.google.app\">",
            "<activity android:name=\"com.google.bar.activityFoo\" />",
            "</application>");

    Path binarySymbols = createFile("R.txt",
        "int attr agility 0x7f010000",
        "int attr dexterity 0x7f010001",
        "int drawable heart 0x7f020000",
        "int id someTextView 0x7f080000",
        "int integer maxNotifications 0x7f090000",
        "int string alphabet 0x7f100000",
        "int string ok 0x7f100001");

    Path jarPath = tempDir.resolve("app_resources.jar");

    RClassGeneratorAction.main(ImmutableList.<String>of(
        "--primaryRTxt", binarySymbols.toString(),
        "--primaryManifest", binaryManifest.toString(),
        "--classJarOutput", jarPath.toString()
    ).toArray(new String[0]));

    assertThat(Files.exists(jarPath)).isTrue();
    assertThat(Files.getLastModifiedTime(jarPath)).isEqualTo(FileTime.fromMillis(0));

    try (ZipFile zip = new ZipFile(jarPath.toFile())) {
      List<? extends ZipEntry> zipEntries = Collections.list(zip.entries());
      Iterable<String> entries = getZipFilenames(zipEntries);
      assertThat(entries)
          .containsExactly(
              Paths.get("com/google/app/R$attr.class").toString(),
              Paths.get("com/google/app/R$drawable.class").toString(),
              Paths.get("com/google/app/R$id.class").toString(),
              Paths.get("com/google/app/R$integer.class").toString(),
              Paths.get("com/google/app/R$string.class").toString(),
              Paths.get("com/google/app/R.class").toString(),
              Paths.get("META-INF/MANIFEST.MF").toString());
    }
  }

  @Test
  public void noBinary() throws Exception {
    Path jarPath = tempDir.resolve("app_resources.jar");
    RClassGeneratorAction.main(ImmutableList.<String>of(
        "--classJarOutput", jarPath.toString()
    ).toArray(new String[0]));

    assertThat(Files.exists(jarPath)).isTrue();
    assertThat(Files.getLastModifiedTime(jarPath)).isEqualTo(FileTime.fromMillis(0));

    try (ZipFile zip = new ZipFile(jarPath.toFile())) {
      List<? extends ZipEntry> zipEntries = Collections.list(zip.entries());
      Iterable<String> entries = getZipFilenames(zipEntries);
      assertThat(entries).containsExactly(Paths.get("META-INF/MANIFEST.MF").toString());
    }
  }

  @Test
  public void customPackageForR() throws Exception {
    Path binaryManifest = ManifestBuilder.of(tempDir.resolve("binary"))
        .createManifest("AndroidManifest.xml", "com.google.app",
            "<application android:name=\"com.google.app\">",
            "<activity android:name=\"com.google.foo.activityFoo\" />",
            "</application>");
    Path libFooManifest = ManifestBuilder.of(tempDir.resolve("libFoo"))
        .createManifest("AndroidManifest.xml", "com.google.foo", "");

    Path binarySymbols = createFile("R.txt",
        "int attr agility 0x7f010000",
        "int integer maxNotifications 0x7f090000",
        "int string ok 0x7f100001");
    Path libFooSymbols = createFile("libFoo.R.txt",
        "int string ok 0x1");
    Path jarPath = tempDir.resolve("app_resources.jar");
    RClassGeneratorAction.main(
        ImmutableList.<String>of(
            "--primaryRTxt",
            binarySymbols.toString(),
            "--primaryManifest",
            binaryManifest.toString(),
            "--packageForR", "com.custom.er",
            "--libraries",
            libFooSymbols + File.pathSeparator + libFooManifest,
            "--classJarOutput",
            jarPath.toString())
            .toArray(new String[0]));

    assertThat(Files.exists(jarPath)).isTrue();
    assertThat(Files.getLastModifiedTime(jarPath)).isEqualTo(FileTime.fromMillis(0));

    try (ZipFile zip = new ZipFile(jarPath.toFile())) {
      List<? extends ZipEntry> zipEntries = Collections.list(zip.entries());
      Iterable<String> entries = getZipFilenames(zipEntries);
      assertThat(entries)
          .containsExactly(
              Paths.get("com/google/foo/R$string.class").toString(),
              Paths.get("com/google/foo/R.class").toString(),
              Paths.get("com/custom/er/R$attr.class").toString(),
              Paths.get("com/custom/er/R$integer.class").toString(),
              Paths.get("com/custom/er/R$string.class").toString(),
              Paths.get("com/custom/er/R.class").toString(),
              Paths.get("META-INF/MANIFEST.MF").toString());
    }
  }

  @Test
  public void noSymbolsNoRClass() throws Exception {
    Path binaryManifest = ManifestBuilder.of(tempDir.resolve("binary"))
        .createManifest("AndroidManifest.xml", "com.google.app",
            "<application android:name=\"com.google.app\">",
            "<activity android:name=\"com.google.foo.activityFoo\" />",
            "</application>");

    Path binarySymbols = createFile("R.txt", "");
    Path jarPath = tempDir.resolve("app_resources.jar");
    RClassGeneratorAction.main(
        ImmutableList.<String>of(
            "--primaryRTxt",
            binarySymbols.toString(),
            "--primaryManifest",
            binaryManifest.toString(),
            "--classJarOutput",
            jarPath.toString())
            .toArray(new String[0]));

    assertThat(Files.exists(jarPath)).isTrue();
    assertThat(Files.getLastModifiedTime(jarPath)).isEqualTo(FileTime.fromMillis(0));

    try (ZipFile zip = new ZipFile(jarPath.toFile())) {
      List<? extends ZipEntry> zipEntries = Collections.list(zip.entries());
      Iterable<String> entries = getZipFilenames(zipEntries);
      assertThat(entries)
          .containsExactly(
              Paths.get("META-INF/MANIFEST.MF").toString());
    }
  }

  private Path createFile(String name, String... contents) throws IOException {
    Path path = tempDir.resolve(name);
    Files.createDirectories(path.getParent());
    Files.newOutputStream(path).write(
        Joiner.on("\n").join(contents).getBytes(StandardCharsets.UTF_8));
    return path;
  }

  private Iterable<String> getZipFilenames(Iterable<? extends ZipEntry> entries) {
    return Iterables.transform(entries,
        new Function<ZipEntry, String>() {
          @Override
          public String apply(ZipEntry input) {
            return input.getName();
          }
        });
  }
}
