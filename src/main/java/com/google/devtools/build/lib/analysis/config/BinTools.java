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

package com.google.devtools.build.lib.analysis.config;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactFactory;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;

import java.io.IOException;

/**
 * Initializes the &lt;execRoot>/_bin/ directory that contains auxiliary tools used during action
 * execution (alarm, etc). The main purpose of this is to make sure that those tools are accessible
 * using relative paths from the execution root.
 */
public final class BinTools {
  private final BlazeDirectories directories;
  private final Path binDir;  // the working bin directory under execRoot
  private final ImmutableList<String> embeddedTools;

  private BinTools(BlazeDirectories directories, ImmutableList<String> tools) {
    this.directories = directories;
    this.binDir = directories.getExecRoot().getRelative("_bin");
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    // Files under embedded_tools shouldn't be copied to under _bin dir
    // They won't be used during action execution time.
    for (String tool : tools) {
      if (!tool.startsWith("embedded_tools/")) {
        builder.add(tool);
      }
    }
    this.embeddedTools = builder.build();
  }

  /**
   * Creates an instance with the list of embedded tools obtained from scanning the directory
   * into which said binaries were extracted by the launcher.
   */
  public static BinTools forProduction(BlazeDirectories directories) throws IOException {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    scanDirectoryRecursively(builder, directories.getEmbeddedBinariesRoot(), "");
    return new BinTools(directories, builder.build());
  }

  /**
   * Creates an empty instance for testing.
   */
  @VisibleForTesting
  public static BinTools empty(BlazeDirectories directories) {
    return new BinTools(directories, ImmutableList.<String>of());
  }

  /**
   * Creates an instance for testing without actually symlinking the tools.
   *
   * <p>Used for tests that need a set of embedded tools to be present, but not the actual files.
   */
  @VisibleForTesting
  public static BinTools forUnitTesting(BlazeDirectories directories, Iterable<String> tools) {
    return new BinTools(directories, ImmutableList.copyOf(tools));
  }

  /**
   * Populates the _bin directory by symlinking the necessary files from the given
   * srcDir, and returns the corresponding BinTools.
   */
  @VisibleForTesting
  public static BinTools forIntegrationTesting(
      BlazeDirectories directories, String srcDir, Iterable<String> tools)
      throws IOException {
    Path srcPath = directories.getOutputBase().getFileSystem().getPath(srcDir);
    for (String embedded : tools) {
      Path runfilesPath = srcPath.getRelative(embedded);
      if (!runfilesPath.isFile()) {
        // The file isn't there - nothing to symlink!
        //
        // Note: This path is usually taken by the tests using the in-memory
        // file system. They can't run the embedded scripts anyhow, so there isn't
        // much point in creating a symlink to a non-existent binary here.
        continue;
      }
      Path outputPath = directories.getExecRoot().getChild("_bin").getChild(embedded);
      if (outputPath.exists()) {
        outputPath.delete();
      }
      FileSystemUtils.createDirectoryAndParents(outputPath.getParentDirectory());
      outputPath.createSymbolicLink(runfilesPath);
    }

    return new BinTools(directories, ImmutableList.copyOf(tools));
  }

  private static void scanDirectoryRecursively(
      ImmutableList.Builder<String> result, Path root, String relative) throws IOException {
    for (Dirent dirent : root.readdir(Symlinks.NOFOLLOW)) {
      String childRelative = relative.isEmpty()
          ? dirent.getName()
          : relative + "/" + dirent.getName();
      switch (dirent.getType()) {
        case FILE:
          result.add(childRelative);
          break;

        case DIRECTORY:
          scanDirectoryRecursively(result, root.getChild(dirent.getName()), childRelative);
          break;

        default:
          // Nothing to do here -- we ignore symlinks, since they should not be present in the
          // embedded binaries tree.
          break;
      }
    }
  }

  public PathFragment getExecPath(String embedPath) {
    if (!embeddedTools.contains(embedPath)) {
      return null;
    }
    return new PathFragment("_bin").getRelative(new PathFragment(embedPath).getBaseName());
  }

  public Artifact getEmbeddedArtifact(String embedPath, ArtifactFactory artifactFactory) {
    PathFragment path = getExecPath(embedPath);
    Preconditions.checkNotNull(path, embedPath + " not found in embedded tools");
    return artifactFactory.getDerivedArtifact(path, binDir.getParentDirectory());
  }

  public ImmutableList<Artifact> getAllEmbeddedArtifacts(ArtifactFactory artifactFactory) {
    ImmutableList.Builder<Artifact> builder = ImmutableList.builder();
    for (String embeddedTool : embeddedTools) {
      builder.add(getEmbeddedArtifact(embeddedTool, artifactFactory));
    }
    return builder.build();
  }

  /**
   * Initializes the build tools not available at absolute paths. Note that
   * these must be constant across all configurations.
   */
  public void setupBuildTools() throws ExecException {
    try {
      FileSystemUtils.createDirectoryAndParents(binDir);
    } catch (IOException e) {
      throw new EnvironmentalExecException("could not create directory '" + binDir  + "'", e);
    }

    for (String embeddedPath : embeddedTools) {
      setupTool(embeddedPath);
    }
  }

  private void setupTool(String embeddedPath) throws ExecException {
    Path sourcePath = directories.getEmbeddedBinariesRoot().getRelative(embeddedPath);
    Path linkPath = binDir.getRelative(new PathFragment(embeddedPath).getBaseName());
    linkTool(sourcePath, linkPath);
  }

  private void linkTool(Path sourcePath, Path linkPath) throws ExecException {
    if (linkPath.getFileSystem().supportsSymbolicLinksNatively()) {
      try {
        if (!linkPath.isSymbolicLink()) {
          // ensureSymbolicLink() does not handle the case where there is already
          // a file with the same name, so we need to handle it here.
          linkPath.delete();
        }
        FileSystemUtils.ensureSymbolicLink(linkPath, sourcePath);
      } catch (IOException e) {
        throw new EnvironmentalExecException("failed to link '" + sourcePath + "'", e);
      }
    } else {
      // For file systems that do not support linking, copy.
      try {
        FileSystemUtils.copyTool(sourcePath, linkPath);
      } catch (IOException e) {
        throw new EnvironmentalExecException("failed to copy '" + sourcePath + "'" , e);
      }
    }
  }
}
