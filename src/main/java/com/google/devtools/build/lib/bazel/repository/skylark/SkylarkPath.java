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

package com.google.devtools.build.lib.bazel.repository.skylark;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.util.List;

/**
 * A Path object to be used into Skylark remote repository.
 *
 * <p>This path object enable non-hermetic operations from Skylark and should not be returned by
 * something other than a SkylarkRepositoryContext.
 */
@Immutable
@SkylarkModule(
  name = "path",
  category = SkylarkModuleCategory.NONE,
  doc = "A structure representing a file to be used inside a repository."
)
final class SkylarkPath {
  private final Path path;

  SkylarkPath(Path path) {
    this.path = path;
  }

  Path getPath() {
    return path;
  }

  @Override
  public boolean equals(Object obj) {
    return (obj instanceof SkylarkPath) &&  path.equals(((SkylarkPath) obj).path);
  }

  @Override
  public int hashCode() {
    return path.hashCode();
  }

  @SkylarkCallable(
    name = "basename",
    structField = true,
    doc = "A string giving the basename of the file."
  )
  public String getBasename() {
    return path.getBaseName();
  }

  @SkylarkCallable(
      name = "readdir",
      structField = false,
      doc = "The list of entries in the directory denoted by this path."
  )
  public List<SkylarkPath> readdir() throws IOException {
    ImmutableList.Builder<SkylarkPath> builder = ImmutableList.builder();
    for (Path p : path.getDirectoryEntries()) {
      builder.add(new SkylarkPath(p));
    }
    return builder.build();
  }

  @SkylarkCallable(
    name = "dirname",
    structField = true,
    doc = "The parent directory of this file, or None if this file does not have a parent."
  )
  public SkylarkPath getDirname() {
    Path parentPath = path.getParentDirectory();
    return parentPath == null ? null : new SkylarkPath(parentPath);
  }

  @SkylarkCallable(
    name = "get_child",
    doc = "Append the given path to this path and return the resulted path."
  )
  public SkylarkPath getChild(String childPath) {
    return new SkylarkPath(path.getChild(childPath));
  }

  @SkylarkCallable(
    name = "exists",
    structField = true,
    doc = "Returns true if the file denoted by this path exists."
  )
  public boolean exists() {
    return path.exists();
  }

  @SkylarkCallable(
    name = "realpath",
    structField = true,
    doc = "Returns the canonical path for this path by repeatedly replacing all symbolic links "
        + "with their referents."
  )
  public SkylarkPath realpath() throws IOException {
    return new SkylarkPath(path.resolveSymbolicLinks());
  }

  @Override
  public String toString() {
    return path.toString();
  }
}
