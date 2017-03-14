// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.vfs;

import com.google.common.testing.EqualsTester;
import com.google.devtools.build.lib.util.BlazeClock;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link RootedPath}.
 */
@RunWith(JUnit4.class)
public class RootedPathTest {
  private FileSystem filesystem;
  private Path root;

  @Before
  public final void initializeFileSystem() throws Exception  {
    filesystem = new InMemoryFileSystem(BlazeClock.instance());
    root = filesystem.getRootDirectory();
  }

  @Test
  public void testEqualsAndHashCodeContract() throws Exception {
    Path pkgRoot1 = root.getRelative("pkgroot1");
    Path pkgRoot2 = root.getRelative("pkgroot2");
    RootedPath rootedPathA1 = RootedPath.toRootedPath(pkgRoot1, new PathFragment("foo/bar"));
    RootedPath rootedPathA2 = RootedPath.toRootedPath(pkgRoot1, new PathFragment("foo/bar"));
    RootedPath absolutePath1 = RootedPath.toRootedPath(root, new PathFragment("pkgroot1/foo/bar"));
    RootedPath rootedPathB1 = RootedPath.toRootedPath(pkgRoot2, new PathFragment("foo/bar"));
    RootedPath rootedPathB2 = RootedPath.toRootedPath(pkgRoot2, new PathFragment("foo/bar"));
    RootedPath absolutePath2 = RootedPath.toRootedPath(root, new PathFragment("pkgroot2/foo/bar"));
    new EqualsTester()
      .addEqualityGroup(rootedPathA1, rootedPathA2)
      .addEqualityGroup(rootedPathB1, rootedPathB2)
      .addEqualityGroup(absolutePath1)
      .addEqualityGroup(absolutePath2)
      .testEquals();
  }
}
