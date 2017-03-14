// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages;

import static org.junit.Assert.assertEquals;

import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test for building external packages.
 */
@RunWith(JUnit4.class)
public class ExternalPackageTest extends BuildViewTestCase {

  private Path workspacePath;

  @Before
  public final void setWorkspacePath() throws Exception {
    workspacePath = rootDirectory.getRelative("WORKSPACE");
  }

  @Test
  public void testMultipleRulesWithSameName() throws Exception {
    FileSystemUtils.writeIsoLatin1(workspacePath,
        "local_repository(",
        "    name = 'my_rule',",
        "    path = '/foo/bar',",
        ")",
        "new_local_repository(",
        "    name = 'my_rule',",
        "    path = '/foo/bar',",
        "    build_file = 'baz',",
        ")");

    invalidatePackages(/*alsoConfigs=*/false);
    // Make sure the second rule "wins."
    assertEquals("new_local_repository rule", getTarget("//external:my_rule").getTargetKind());
  }

  @Test
  public void testOverridingBindRules() throws Exception {
    FileSystemUtils.writeIsoLatin1(workspacePath,
        "bind(",
        "    name = 'my_rule',",
        "    actual = '//foo:bar',",
        ")",
        "new_local_repository(",
        "    name = 'my_rule',",
        "    path = '/foo/bar',",
        "    build_file = 'baz',",
        ")");

    invalidatePackages(/*alsoConfigs=*/false);
    // Make sure the second rule "wins."
    assertEquals("new_local_repository rule", getTarget("//external:my_rule").getTargetKind());
  }
}
