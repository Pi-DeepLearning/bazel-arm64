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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifactType;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactOwner;
import com.google.devtools.build.lib.actions.ArtifactPrefixConflictException;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.actions.Root;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.actions.SpawnActionTemplate;
import com.google.devtools.build.lib.analysis.actions.SpawnActionTemplate.OutputPathMapper;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.skyframe.ArtifactSkyKey.OwnedArtifact;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.util.Preconditions;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.RecordingDifferencer;
import com.google.devtools.build.skyframe.SequentialBuildDriver;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ActionTemplateExpansionFunction}. */
@RunWith(JUnit4.class)
public final class ActionTemplateExpansionFunctionTest extends FoundationTestCase  {
  private Map<Artifact, TreeArtifactValue> artifactValueMap;
  private SequentialBuildDriver driver;

  @Before
  public void setUp() throws Exception  {
    artifactValueMap = new LinkedHashMap<>();
    AtomicReference<PathPackageLocator> pkgLocator = new AtomicReference<>(new PathPackageLocator(
        rootDirectory.getFileSystem().getPath("/outputbase"), ImmutableList.of(rootDirectory)));
    RecordingDifferencer differencer = new RecordingDifferencer();
    MemoizingEvaluator evaluator =
        new InMemoryMemoizingEvaluator(
            ImmutableMap.<SkyFunctionName, SkyFunction>builder()
                .put(SkyFunctions.ARTIFACT,
                    new DummyArtifactFunction(artifactValueMap))
                .put(SkyFunctions.ACTION_TEMPLATE_EXPANSION, new ActionTemplateExpansionFunction())
                .build(),
            differencer);
    driver = new SequentialBuildDriver(evaluator);
    PrecomputedValue.BUILD_ID.set(differencer, UUID.randomUUID());
    PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, pkgLocator.get());
  }

  @Test
  public void testActionTemplateExpansionFunction() throws Exception {
    Artifact inputTreeArtifact = createAndPopulateTreeArtifact(
        "inputTreeArtifact", "child0", "child1", "child2");
    Artifact outputTreeArtifact = createTreeArtifact("outputTreeArtifact");

    SpawnActionTemplate spawnActionTemplate = ActionsTestUtil.createDummySpawnActionTemplate(
        inputTreeArtifact, outputTreeArtifact);
    List<Action> actions = evaluate(spawnActionTemplate);
    assertThat(actions).hasSize(3);

    ArtifactOwner owner = ActionTemplateExpansionValue.createActionTemplateExpansionKey(
        spawnActionTemplate);
    int i = 0;
    for (Action action : actions) {
      String childName = "child" + i;
      assertThat(Artifact.toExecPaths(action.getInputs())).contains(
          "out/inputTreeArtifact/" + childName);
      assertThat(Artifact.toExecPaths(action.getOutputs())).containsExactly(
          "out/outputTreeArtifact/" + childName);
      assertThat(Iterables.getOnlyElement(action.getOutputs()).getArtifactOwner()).isEqualTo(owner);
      ++i;
    }
  }

  @Test
  public void testThrowsOnActionConflict() throws Exception {
    Artifact inputTreeArtifact = createAndPopulateTreeArtifact(
        "inputTreeArtifact", "child0", "child1", "child2");
    Artifact outputTreeArtifact = createTreeArtifact("outputTreeArtifact");


    OutputPathMapper mapper = new OutputPathMapper() {
      @Override
      public PathFragment parentRelativeOutputPath(TreeFileArtifact inputTreeFileArtifact) {
        return new PathFragment("conflict_path");
      }
    };
    SpawnActionTemplate spawnActionTemplate =
        new SpawnActionTemplate.Builder(inputTreeArtifact, outputTreeArtifact)
            .setExecutable(new PathFragment("/bin/cp"))
            .setCommandLineTemplate(CustomCommandLine.builder().build())
            .setOutputPathMapper(mapper)
            .build(ActionsTestUtil.NULL_ACTION_OWNER);

    try {
       evaluate(spawnActionTemplate);
       fail("Expected ActionConflictException");
    } catch (ActionConflictException e) {
       // Expected ActionConflictException
    }
  }

  @Test
  public void testThrowsOnArtifactPrefixConflict() throws Exception {
    Artifact inputTreeArtifact = createAndPopulateTreeArtifact(
        "inputTreeArtifact", "child0", "child1", "child2");
    Artifact outputTreeArtifact = createTreeArtifact("outputTreeArtifact");

    OutputPathMapper mapper = new OutputPathMapper() {
      private int i = 0;
      @Override
      public PathFragment parentRelativeOutputPath(TreeFileArtifact inputTreeFileArtifact) {
        PathFragment path;
        switch (i) {
          case 0:
            path = new PathFragment("path_prefix");
            break;
          case 1:
            path = new PathFragment("path_prefix/conflict");
            break;
          default:
            path = inputTreeFileArtifact.getParentRelativePath();
        }

        ++i;
        return path;
      }
    };
    SpawnActionTemplate spawnActionTemplate =
        new SpawnActionTemplate.Builder(inputTreeArtifact, outputTreeArtifact)
            .setExecutable(new PathFragment("/bin/cp"))
            .setCommandLineTemplate(CustomCommandLine.builder().build())
            .setOutputPathMapper(mapper)
            .build(ActionsTestUtil.NULL_ACTION_OWNER);

    try {
       evaluate(spawnActionTemplate);
       fail("Expected ArtifactPrefixConflictException");
    } catch (ArtifactPrefixConflictException e) {
       // Expected ArtifactPrefixConflictException
    }
  }

  private List<Action> evaluate(SpawnActionTemplate spawnActionTemplate) throws Exception {
    SkyKey skyKey = ActionTemplateExpansionValue.key(spawnActionTemplate);
    EvaluationResult<ActionTemplateExpansionValue> result = driver.evaluate(
        ImmutableList.of(skyKey),
        false,
        SkyframeExecutor.DEFAULT_THREAD_COUNT,
        NullEventHandler.INSTANCE);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    return ImmutableList.copyOf(result.get(skyKey).getExpandedActions());
  }

  private Artifact createTreeArtifact(String path) {
    PathFragment execPath = new PathFragment("out").getRelative(path);
    Path fullPath = rootDirectory.getRelative(execPath);
    return new SpecialArtifact(
        fullPath,
        Root.asDerivedRoot(rootDirectory, rootDirectory.getRelative("out")),
        execPath,
        ArtifactOwner.NULL_OWNER,
        SpecialArtifactType.TREE);
  }

  private Artifact createAndPopulateTreeArtifact(String path, String... childRelativePaths)
      throws Exception {
    Artifact treeArtifact = createTreeArtifact(path);
    Map<TreeFileArtifact, FileArtifactValue> treeFileArtifactMap = new LinkedHashMap<>();

    for (String childRelativePath : childRelativePaths) {
      TreeFileArtifact treeFileArtifact = ActionInputHelper.treeFileArtifact(
          treeArtifact, new PathFragment(childRelativePath));
      scratch.file(treeFileArtifact.getPath().toString(), childRelativePath);
      // We do not care about the FileArtifactValues in this test.
      treeFileArtifactMap.put(treeFileArtifact, FileArtifactValue.create(treeFileArtifact));
    }

    artifactValueMap.put(
        treeArtifact, TreeArtifactValue.create(ImmutableMap.copyOf(treeFileArtifactMap)));

    return treeArtifact;
  }

  /** Dummy ArtifactFunction that just returns injected values */
  private static class DummyArtifactFunction implements SkyFunction {
    private final Map<Artifact, TreeArtifactValue> artifactValueMap;

    DummyArtifactFunction(Map<Artifact, TreeArtifactValue> artifactValueMap) {
      this.artifactValueMap = artifactValueMap;
    }
    @Override
    public SkyValue compute(SkyKey skyKey, Environment env) {
      OwnedArtifact ownedArtifact = (OwnedArtifact) skyKey.argument();
      Artifact artifact = ownedArtifact.getArtifact();
      return Preconditions.checkNotNull(artifactValueMap.get(artifact));
    }

    @Override
    public String extractTag(SkyKey skyKey) {
      return null;
    }
  }
}
