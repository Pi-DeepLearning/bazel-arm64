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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.vfs.util.FileSystems;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;

/**
 * A test for {@link Path} in the context of {@link UnixFileSystem}.
 */
@RunWith(JUnit4.class)
public class UnixPathGetParentTest {

  private FileSystem unixFs;
  private Path testRoot;

  @Before
  public final void createTestRoot() throws Exception  {
    unixFs = FileSystems.getNativeFileSystem();
    testRoot = unixFs.getPath(TestUtils.tmpDir()).getRelative("UnixPathGetParentTest");
    FileSystemUtils.createDirectoryAndParents(testRoot);
  }

  @After
  public final void deleteTestRoot() throws Exception  {
    FileSystemUtils.deleteTree(testRoot); // (comment out during debugging)
  }

  private Path getParent(String path) {
    return unixFs.getPath(path).getParentDirectory();
  }

  @Test
  public void testAbsoluteRootHasNoParent() {
    assertNull(getParent("/"));
  }

  @Test
  public void testParentOfSimpleDirectory() {
    assertEquals("/foo", getParent("/foo/bar").getPathString());
  }

  @Test
  public void testParentOfDotDotInMiddleOfPathname() {
    assertEquals("/", getParent("/foo/../bar").getPathString());
  }

  @Test
  public void testGetPathDoesNormalizationWithoutIO() throws IOException {
    Path tmp = testRoot.getChild("tmp");
    Path tmpWiz = tmp.getChild("wiz");

    tmp.createDirectory();

    // ln -sf /tmp /tmp/wiz
    tmpWiz.createSymbolicLink(tmp);

    assertEquals(testRoot, tmp.getParentDirectory());

    assertEquals(tmp, tmpWiz.getParentDirectory());

    // Under UNIX, inode(/tmp/wiz/..) == inode(/).  However getPath() does not
    // perform I/O, only string operations, so it disagrees:
    assertEquals(tmp, tmp.getRelative(new PathFragment("wiz/..")));
  }
}
