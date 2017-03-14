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
package com.google.devtools.build.lib.rules.test;

import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.util.Pair;

/**
 * An implementation class for the InstrumentedFilesProvider interface.
 */
public final class InstrumentedFilesProviderImpl implements InstrumentedFilesProvider {
  public static final InstrumentedFilesProvider EMPTY =
      new InstrumentedFilesProviderImpl(
          NestedSetBuilder.<Artifact>emptySet(Order.STABLE_ORDER),
          NestedSetBuilder.<Artifact>emptySet(Order.STABLE_ORDER),
          NestedSetBuilder.<Artifact>emptySet(Order.STABLE_ORDER),
          NestedSetBuilder.<Artifact>emptySet(Order.STABLE_ORDER),
          NestedSetBuilder.<Artifact>emptySet(Order.STABLE_ORDER),
          NestedSetBuilder.<Pair<String, String>>emptySet(Order.COMPILE_ORDER));

  private final NestedSet<Artifact> instrumentedFiles;
  private final NestedSet<Artifact> instrumentationMetadataFiles;
  private final NestedSet<Artifact> baselineCoverageFiles;
  private final NestedSet<Artifact> baselineCoverageArtifacts;
  private final NestedSet<Artifact> coverageSupportFiles;
  private final NestedSet<Pair<String, String>> coverageEnvironment;

  public InstrumentedFilesProviderImpl(
      NestedSet<Artifact> instrumentedFiles,
      NestedSet<Artifact> instrumentationMetadataFiles,
      NestedSet<Artifact> baselineCoverageFiles,
      NestedSet<Artifact> baselineCoverageArtifacts,
      NestedSet<Artifact> coverageSupportFiles,
      NestedSet<Pair<String, String>> coverageEnvironment) {
    this.instrumentedFiles = instrumentedFiles;
    this.instrumentationMetadataFiles = instrumentationMetadataFiles;
    this.baselineCoverageFiles = baselineCoverageFiles;
    this.baselineCoverageArtifacts = baselineCoverageArtifacts;
    this.coverageSupportFiles = coverageSupportFiles;
    this.coverageEnvironment = coverageEnvironment;
  }

  @Override
  public NestedSet<Artifact> getInstrumentedFiles() {
    return instrumentedFiles;
  }

  @Override
  public NestedSet<Artifact> getInstrumentationMetadataFiles() {
    return instrumentationMetadataFiles;
  }

  @Override
  public NestedSet<Artifact> getBaselineCoverageInstrumentedFiles() {
    return baselineCoverageFiles;
  }

  @Override
  public NestedSet<Artifact> getBaselineCoverageArtifacts() {
    return baselineCoverageArtifacts;
  }

  @Override
  public NestedSet<Artifact> getCoverageSupportFiles() {
    return coverageSupportFiles;
  }

  @Override
  public NestedSet<Pair<String, String>> getCoverageEnvironment() {
    return coverageEnvironment;
  }
}
