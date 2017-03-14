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

package com.google.devtools.build.android.ideinfo;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.google.common.base.Joiner;
import com.google.devtools.build.lib.ideinfo.androidstudio.PackageManifestOuterClass.ArtifactLocation;
import com.google.devtools.common.options.OptionsParsingException;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.nio.file.Paths;

/**
 * Tests {@link ArtifactLocationConverter}.
 */
@RunWith(JUnit4.class)
public class ArtifactLocationConverterTest {

  private ArtifactLocationConverter converter;

  @Before
  public final void init() throws Exception  {
    converter = new ArtifactLocationConverter();
  }

  @Test
  public void testConverterSourceArtifact() throws Exception {
    ArtifactLocation parsed = converter.convert(
        Joiner.on(',').join("", "test.java")
    );
    assertThat(parsed)
        .isEqualTo(
            ArtifactLocation.newBuilder()
                .setRelativePath(Paths.get("test.java").toString())
                .setIsSource(true)
                .build());
  }

  @Test
  public void testConverterDerivedArtifact() throws Exception {
    ArtifactLocation parsed = converter.convert(
        Joiner.on(',').join("bin", "java/com/test.java")
    );
    assertThat(parsed)
        .isEqualTo(
            ArtifactLocation.newBuilder()
                .setRootExecutionPathFragment(Paths.get("bin").toString())
                .setRelativePath(Paths.get("java/com/test.java").toString())
                .setIsSource(false)
                .build());
  }

  @Test
  public void testInvalidFormatFails() throws Exception {
    assertFails("/root", "Expected either 2 or 3 comma-separated paths");
    assertFails("/root,exec,rel,extra", "Expected either 2 or 3 comma-separated paths");
  }

  @Test
  public void testOldFormat() throws Exception {
    ArtifactLocation parsed = converter
        .convert("bin/out,java/com/test.java,/usr/local/_tmp/code/bin/out");
    assertThat(parsed)
        .isEqualTo(
            ArtifactLocation.newBuilder()
                .setRootExecutionPathFragment(Paths.get("bin/out").toString())
                .setRelativePath(Paths.get("java/com/test.java").toString())
                .setIsSource(false)
                .build());
  }


  private void assertFails(String input, String expectedError) {
    try {
      new ArtifactLocationConverter().convert(input);
      fail();
    } catch (OptionsParsingException e) {
      assertThat(e).hasMessage(expectedError);
    }
  }
}

