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
package com.google.devtools.build.lib.analysis.util;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactFactory;
import com.google.devtools.build.lib.actions.ArtifactOwner;
import com.google.devtools.build.lib.actions.ExecutionStrategy;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.MiddlemanFactory;
import com.google.devtools.build.lib.actions.MutableActionGraph;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.Root;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.AnalysisEnvironment;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.OutputGroupProvider;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TopLevelArtifactContext;
import com.google.devtools.build.lib.analysis.WorkspaceStatusAction;
import com.google.devtools.build.lib.analysis.WorkspaceStatusAction.Key;
import com.google.devtools.build.lib.analysis.buildinfo.BuildInfoFactory.BuildInfoKey;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationCollection;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Utilities for analysis phase tests.
 */
public final class AnalysisTestUtil {

  /**
   * TopLevelArtifactContext that should be sufficient for testing.
   */
  public static final TopLevelArtifactContext TOP_LEVEL_ARTIFACT_CONTEXT =
      new TopLevelArtifactContext(
          /*runTestsExclusively=*/false,
          /*outputGroups=*/ImmutableSortedSet.copyOf(OutputGroupProvider.DEFAULT_GROUPS));

  /**
   * An {@link AnalysisEnvironment} implementation that collects the actions registered.
   */
  public static class CollectingAnalysisEnvironment implements AnalysisEnvironment {
    private final List<ActionAnalysisMetadata> actions = new ArrayList<>();
    private final AnalysisEnvironment original;

    public CollectingAnalysisEnvironment(AnalysisEnvironment original) {
      this.original = original;
    }

    public void clear() {
      actions.clear();
    }

    @Override
    public void registerAction(ActionAnalysisMetadata... actions) {
      Collections.addAll(this.actions, actions);
      original.registerAction(actions);
    }

    /** Calls {@link MutableActionGraph#registerAction} for all collected actions. */
    public void registerWith(MutableActionGraph actionGraph) {
      for (ActionAnalysisMetadata action : actions) {
        try {
          actionGraph.registerAction(action);
        } catch (ActionConflictException e) {
          throw new ActionsTestUtil.UncheckedActionConflictException(e);
        }
      }
    }

    @Override
    public EventHandler getEventHandler() {
      return original.getEventHandler();
    }

    @Override
    public boolean hasErrors() {
      return original.hasErrors();
    }

    @Override
    public Artifact getDerivedArtifact(PathFragment rootRelativePath, Root root) {
      return original.getDerivedArtifact(rootRelativePath, root);
    }

    @Override
    public Artifact getConstantMetadataArtifact(PathFragment rootRelativePath, Root root) {
      return original.getConstantMetadataArtifact(rootRelativePath, root);
    }

    @Override
    public Artifact getTreeArtifact(PathFragment rootRelativePath, Root root) {
      return null;
    }

    @Override
    public Artifact getFilesetArtifact(PathFragment rootRelativePath, Root root) {
      return original.getFilesetArtifact(rootRelativePath, root);
    }

    @Override
    public Artifact getEmbeddedToolArtifact(String embeddedPath) {
      return original.getEmbeddedToolArtifact(embeddedPath);
    }

    @Override
    public MiddlemanFactory getMiddlemanFactory() {
      return original.getMiddlemanFactory();
    }

    @Override
    public ActionAnalysisMetadata getLocalGeneratingAction(Artifact artifact) {
      return original.getLocalGeneratingAction(artifact);
    }

    @Override
    public Iterable<ActionAnalysisMetadata> getRegisteredActions() {
      return original.getRegisteredActions();
    }

    @Override
    public SkyFunction.Environment getSkyframeEnv() {
      return null;
    }

    @Override
    public Artifact getStableWorkspaceStatusArtifact() throws InterruptedException {
      return original.getStableWorkspaceStatusArtifact();
    }

    @Override
    public Artifact getVolatileWorkspaceStatusArtifact() throws InterruptedException {
      return original.getVolatileWorkspaceStatusArtifact();
    }

    @Override
    public ImmutableList<Artifact> getBuildInfo(
        RuleContext ruleContext, BuildInfoKey key, BuildConfiguration config)
        throws InterruptedException {
      return original.getBuildInfo(ruleContext, key, config);
    }

    @Override
    public ArtifactOwner getOwner() {
      return original.getOwner();
    }

    @Override
    public ImmutableSet<Artifact> getOrphanArtifacts() {
      return original.getOrphanArtifacts();
    }
  }

  @Immutable
  public static final class DummyWorkspaceStatusAction extends WorkspaceStatusAction {
    private final String key;
    private final Artifact stableStatus;
    private final Artifact volatileStatus;

    public DummyWorkspaceStatusAction(String key,
        Artifact stableStatus, Artifact volatileStatus) {
      super(
          ActionOwner.SYSTEM_ACTION_OWNER,
          ImmutableList.<Artifact>of(),
          ImmutableList.of(stableStatus, volatileStatus));
      this.key = key;
      this.stableStatus = stableStatus;
      this.volatileStatus = volatileStatus;
    }

    @Override
    public void execute(ActionExecutionContext actionExecutionContext)
        throws ActionExecutionException {
      try {
        FileSystemUtils.writeContent(stableStatus.getPath(), new byte[] {});
        FileSystemUtils.writeContent(volatileStatus.getPath(), new byte[] {});
      } catch (IOException e) {
        throw new ActionExecutionException(e, this, true);
      }
    }

    @Override
    public String getMnemonic() {
      return "DummyBuildInfoAction" + key;
    }

    @Override
    public ResourceSet estimateResourceConsumption(Executor executor) {
      return ResourceSet.ZERO;
    }

    @Override
    public String computeKey() {
      return "";
    }

    @Override
    public Artifact getVolatileStatus() {
      return volatileStatus;
    }

    @Override
    public Artifact getStableStatus() {
      return stableStatus;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof DummyWorkspaceStatusAction)) {
        return false;
      }

      DummyWorkspaceStatusAction that = (DummyWorkspaceStatusAction) o;
      return that.key.equals(this.key);
    }
  }

  @ExecutionStrategy(contextType = WorkspaceStatusAction.Context.class)
  public static class DummyWorkspaceStatusActionContext implements WorkspaceStatusAction.Context {
    @Override
    public ImmutableMap<String, Key> getStableKeys() {
      return ImmutableMap.of();
    }

    @Override
    public ImmutableMap<String, Key> getVolatileKeys() {
      return ImmutableMap.of();
    }
  }

  /**
   * A workspace status action factory that does not do any interaction with the environment.
   */
  public static class DummyWorkspaceStatusActionFactory implements WorkspaceStatusAction.Factory {
    private final BlazeDirectories directories;
    private String key;

    public DummyWorkspaceStatusActionFactory(BlazeDirectories directories) {
      this.directories = directories;
      this.key = "";
    }

    public void setKey(String key) {
      this.key = key;
    }

    @Override
    public WorkspaceStatusAction createWorkspaceStatusAction(
        ArtifactFactory artifactFactory, ArtifactOwner artifactOwner, Supplier<UUID> buildId,
        String workspaceName) {
      Artifact stableStatus = artifactFactory.getDerivedArtifact(
          new PathFragment("build-info.txt"),
          directories.getBuildDataDirectory(workspaceName), artifactOwner);
      Artifact volatileStatus = artifactFactory.getConstantMetadataArtifact(
          new PathFragment("build-changelist.txt"),
          directories.getBuildDataDirectory(workspaceName), artifactOwner);
      return new DummyWorkspaceStatusAction(key, stableStatus, volatileStatus);
    }

    @Override
    public Map<String, String> createDummyWorkspaceStatus() {
      return ImmutableMap.of();
    }
  }

  public static final AnalysisEnvironment STUB_ANALYSIS_ENVIRONMENT = new StubAnalysisEnvironment();

  public static class StubAnalysisEnvironment implements AnalysisEnvironment {
    @Override
    public void registerAction(ActionAnalysisMetadata... action) {
    }

    @Override
    public boolean hasErrors() {
      return false;
    }

    @Override
    public Artifact getEmbeddedToolArtifact(String embeddedPath) {
      return null;
    }

    @Override
    public Artifact getConstantMetadataArtifact(PathFragment rootRelativePath, Root root) {
      return null;
    }

    @Override
    public Artifact getTreeArtifact(PathFragment rootRelativePath, Root root) {
      return null;
    }

    @Override
    public EventHandler getEventHandler() {
      return null;
    }

    @Override
    public MiddlemanFactory getMiddlemanFactory() {
      return null;
    }

    @Override
    public Action getLocalGeneratingAction(Artifact artifact) {
      return null;
    }

    @Override
    public Iterable<ActionAnalysisMetadata> getRegisteredActions() {
      return ImmutableList.of();
    }

    @Override
    public SkyFunction.Environment getSkyframeEnv() {
      return null;
    }

    @Override
    public Artifact getFilesetArtifact(PathFragment rootRelativePath, Root root) {
      return null;
    }

    @Override
    public Artifact getDerivedArtifact(PathFragment rootRelativePath, Root root) {
      return null;
    }

    @Override
    public Artifact getStableWorkspaceStatusArtifact() {
      return null;
    }

    @Override
    public Artifact getVolatileWorkspaceStatusArtifact() {
      return null;
    }

    @Override
    public ImmutableList<Artifact> getBuildInfo(RuleContext ruleContext, BuildInfoKey key,
        BuildConfiguration config) {
      return ImmutableList.of();
    }

    @Override
    public ArtifactOwner getOwner() {
      return ArtifactOwner.NULL_OWNER;
    }

    @Override
    public ImmutableSet<Artifact> getOrphanArtifacts() {
      return ImmutableSet.<Artifact>of();
    }
  };

  /**
   * Matches the output path prefix contributed by a C++ configuration fragment.
   */
  public static final Pattern OUTPUT_PATH_CPP_PREFIX_PATTERN =
      Pattern.compile("(?<=" + TestConstants.PRODUCT_NAME + "-out/)gcc[^/]*-grte-\\w+-");

  /**
   * Given a collection of Artifacts, returns a corresponding set of strings of
   * the form "{root} {relpath}", such as "bin x/libx.a".  Such strings make
   * assertions easier to write.
   *
   * <p>The returned set preserves the order of the input.
   */
  public static Set<String> artifactsToStrings(BuildConfigurationCollection configurations,
      Iterable<Artifact> artifacts) {
    Map<String, String> rootMap = new HashMap<>();
    BuildConfiguration targetConfiguration =
        Iterables.getOnlyElement(configurations.getTargetConfigurations());
    rootMap.put(
        targetConfiguration.getBinDirectory(RepositoryName.MAIN).getPath().toString(),
        "bin");
    rootMap.put(
        targetConfiguration.getGenfilesDirectory(RepositoryName.MAIN).getPath().toString(),
        "genfiles");
    rootMap.put(
        targetConfiguration.getMiddlemanDirectory(RepositoryName.MAIN).getPath().toString(),
        "internal");

    BuildConfiguration hostConfiguration = configurations.getHostConfiguration();
    rootMap.put(
        hostConfiguration.getBinDirectory(RepositoryName.MAIN).getPath().toString(),
        "bin(host)");
    rootMap.put(
        hostConfiguration.getGenfilesDirectory(RepositoryName.MAIN).getPath().toString(),
        "genfiles(host)");
    rootMap.put(
        hostConfiguration.getMiddlemanDirectory(RepositoryName.MAIN).getPath().toString(),
        "internal(host)");

    if (targetConfiguration.useDynamicConfigurations()) {
      // With dynamic configurations, the output paths that bin, genfiles, etc. refer to may
      // or may not include the C++-contributed pieces. e.g. they may be
      // bazel-out/gcc-X-glibc-Y-k8-fastbuild/ or they may be bazel-out/fastbuild/. This code
      // adds support for the non-C++ case, too.
      Map<String, String> prunedRootMap = new HashMap<>();
      for (Map.Entry<String, String> root : rootMap.entrySet()) {
        prunedRootMap.put(
            OUTPUT_PATH_CPP_PREFIX_PATTERN.matcher(root.getKey()).replaceFirst(""),
            root.getValue()
        );
      }
      rootMap.putAll(prunedRootMap);
    }

    Set<String> files = new LinkedHashSet<>();
    for (Artifact artifact : artifacts) {
      Root root = artifact.getRoot();
      if (root.isSourceRoot()) {
        files.add("src " + artifact.getRootRelativePath());
      } else {
        String name = rootMap.get(root.getPath().toString());
        if (name == null) {
          name = "/";
        }
        files.add(name + " " + artifact.getRootRelativePath());
      }
    }
    return files;
  }

}
