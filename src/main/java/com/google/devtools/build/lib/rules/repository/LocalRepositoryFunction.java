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

package com.google.devtools.build.lib.rules.repository;

import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.skyframe.FileValue;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import java.io.IOException;
import java.util.Map;

/**
 * Access a repository on the local filesystem.
 */
public class LocalRepositoryFunction extends RepositoryFunction {

  @Override
  public boolean isLocal(Rule rule) {
    return true;
  }

  @Override
  public RepositoryDirectoryValue.Builder fetch(Rule rule, Path outputDirectory,
      BlazeDirectories directories, Environment env, Map<String, String> markerData)
      throws InterruptedException, RepositoryFunctionException {
    PathFragment pathFragment = RepositoryFunction.getTargetPath(rule, directories.getWorkspace());
    try {
      outputDirectory.createSymbolicLink(pathFragment);
    } catch (IOException e) {
      throw new RepositoryFunctionException(
          new IOException("Could not create symlink to repository " + pathFragment + ": "
              + e.getMessage(), e), Transience.TRANSIENT);
    }
    FileValue repositoryValue = getRepositoryDirectory(outputDirectory, env);
    if (repositoryValue == null) {
      // TODO(bazel-team): If this returns null, we unnecessarily recreate the symlink above on the
      // second execution.
      return null;
    }

    if (!repositoryValue.isDirectory()) {
      throw new RepositoryFunctionException(
          new IOException(rule + " must specify an existing directory"), Transience.TRANSIENT);
    }

    return RepositoryDirectoryValue.builder().setPath(outputDirectory);
  }

  @Override
  public Class<? extends RuleDefinition> getRuleDefinition() {
    return LocalRepositoryRule.class;
  }
}
