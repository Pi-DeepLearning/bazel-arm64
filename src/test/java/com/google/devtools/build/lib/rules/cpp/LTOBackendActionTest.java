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
package com.google.devtools.build.lib.rules.cpp;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.util.ActionTester;
import com.google.devtools.build.lib.analysis.util.ActionTester.ActionCombinationFactory;
import com.google.devtools.build.lib.analysis.util.AnalysisTestUtil;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.exec.util.TestExecutorBuilder;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests {@link com.google.devtools.build.lib.rules.cpp.LTOBackendAction}. */
@RunWith(JUnit4.class)
public class LTOBackendActionTest extends BuildViewTestCase {
  private Artifact bitcode1Artifact;
  private Artifact bitcode2Artifact;
  private Artifact index1Artifact;
  private Artifact index2Artifact;
  private Artifact imports1Artifact;
  private Artifact imports2Artifact;
  private Artifact destinationArtifact;
  private Map<PathFragment, Artifact> allBitcodeFiles;
  private AnalysisTestUtil.CollectingAnalysisEnvironment collectingAnalysisEnvironment;
  private Executor executor;
  private ActionExecutionContext context;

  @Before
  public final void createArtifacts() throws Exception {
    collectingAnalysisEnvironment =
        new AnalysisTestUtil.CollectingAnalysisEnvironment(getTestAnalysisEnvironment());
    bitcode1Artifact = getSourceArtifact("bitcode1.o");
    bitcode2Artifact = getSourceArtifact("bitcode2.o");
    index1Artifact = getSourceArtifact("bitcode1.thinlto.bc");
    index2Artifact = getSourceArtifact("bitcode2.thinlto.bc");
    scratch.file("bitcode1.imports");
    scratch.file("bitcode2.imports", "bitcode1.o");
    imports1Artifact = getSourceArtifact("bitcode1.imports");
    imports2Artifact = getSourceArtifact("bitcode2.imports");
    destinationArtifact = getBinArtifactWithNoOwner("output");
    allBitcodeFiles = new HashMap<>();
    allBitcodeFiles.put(bitcode1Artifact.getExecPath(), bitcode1Artifact);
    allBitcodeFiles.put(bitcode2Artifact.getExecPath(), bitcode2Artifact);
  }

  @Before
  public final void createExecutorAndContext() throws Exception {
    executor = new TestExecutorBuilder(directories, binTools).build();
    context = new ActionExecutionContext(executor, null, null, new FileOutErr(),
        ImmutableMap.<String, String>of(), null);
  }

  @Test
  public void testEmptyImports() throws Exception {
    Action[] actions =
        new LTOBackendAction.Builder()
            .addImportsInfo(allBitcodeFiles, imports1Artifact)
            .addInput(bitcode1Artifact)
            .addInput(index1Artifact)
            .addOutput(destinationArtifact)
            .setExecutable(scratch.file("/bin/clang").asFragment())
            .setProgressMessage("Test")
            .build(ActionsTestUtil.NULL_ACTION_OWNER, collectingAnalysisEnvironment, targetConfig);
    collectingAnalysisEnvironment.registerAction(actions);
    LTOBackendAction action = (LTOBackendAction) actions[0];
    assertEquals(ActionsTestUtil.NULL_ACTION_OWNER.getLabel(), action.getOwner().getLabel());
    assertThat(action.getInputs()).containsExactly(bitcode1Artifact, index1Artifact);
    assertThat(action.getOutputs()).containsExactly(destinationArtifact);
    assertEquals(AbstractAction.DEFAULT_RESOURCE_SET, action.getSpawn().getLocalResources());
    assertThat(action.getArguments()).containsExactly("/bin/clang");
    assertEquals("Test", action.getProgressMessage());
    assertThat(action.inputsKnown()).isFalse();

    // Discover inputs, which should not add any inputs since bitcode1.imports is empty.
    action.discoverInputs(context);
    assertThat(action.inputsKnown()).isTrue();
    assertThat(action.getInputs()).containsExactly(bitcode1Artifact, index1Artifact);
  }

  @Test
  public void testNonEmptyImports() throws Exception {
    Action[] actions =
        new LTOBackendAction.Builder()
            .addImportsInfo(allBitcodeFiles, imports2Artifact)
            .addInput(bitcode2Artifact)
            .addInput(index2Artifact)
            .addOutput(destinationArtifact)
            .setExecutable(scratch.file("/bin/clang").asFragment())
            .setProgressMessage("Test")
            .build(ActionsTestUtil.NULL_ACTION_OWNER, collectingAnalysisEnvironment, targetConfig);
    collectingAnalysisEnvironment.registerAction(actions);
    LTOBackendAction action = (LTOBackendAction) actions[0];
    assertEquals(ActionsTestUtil.NULL_ACTION_OWNER.getLabel(), action.getOwner().getLabel());
    assertThat(action.getInputs()).containsExactly(bitcode2Artifact, index2Artifact);
    assertThat(action.getOutputs()).containsExactly(destinationArtifact);
    assertEquals(AbstractAction.DEFAULT_RESOURCE_SET, action.getSpawn().getLocalResources());
    assertThat(action.getArguments()).containsExactly("/bin/clang");
    assertEquals("Test", action.getProgressMessage());
    assertThat(action.inputsKnown()).isFalse();

    // Discover inputs, which should add bitcode1.o which is listed in bitcode2.imports.
    action.discoverInputs(context);
    assertThat(action.inputsKnown()).isTrue();
    assertThat(action.getInputs())
        .containsExactly(bitcode1Artifact, bitcode2Artifact, index2Artifact);
  }

  @Test
  public void testComputeKey() throws Exception {
    final Artifact artifactA = getSourceArtifact("a");
    final Artifact artifactB = getSourceArtifact("b");
    final Artifact artifactAimports = getSourceArtifact("a.imports");
    final Artifact artifactBimports = getSourceArtifact("b.imports");

    ActionTester.runTest(
        64,
        new ActionCombinationFactory() {
          @Override
          public Action generate(int i) {
            LTOBackendAction.Builder builder = new LTOBackendAction.Builder();
            builder.addOutput(destinationArtifact);

            PathFragment executable =
                (i & 1) == 0 ? artifactA.getExecPath() : artifactB.getExecPath();
            builder.setExecutable(executable);

            if ((i & 2) == 0) {
              builder.addImportsInfo(new HashMap<PathFragment, Artifact>(), artifactAimports);
            } else {
              builder.addImportsInfo(new HashMap<PathFragment, Artifact>(), artifactBimports);
            }

            builder.setMnemonic((i & 4) == 0 ? "a" : "b");

            if ((i & 8) == 0) {
              builder.addInputManifest(artifactA, new PathFragment("a"));
            } else {
              builder.addInputManifest(artifactB, new PathFragment("a"));
            }

            if ((i & 16) == 0) {
              builder.addInput(artifactA);
            } else {
              builder.addInput(artifactB);
            }

            Map<String, String> env = new HashMap<>();
            if ((i & 32) == 0) {
              env.put("foo", "bar");
            }
            builder.setEnvironment(env);

            Action[] actions =
                builder.build(
                    ActionsTestUtil.NULL_ACTION_OWNER, collectingAnalysisEnvironment, targetConfig);
            collectingAnalysisEnvironment.registerAction(actions);
            return actions[0];
          }
        });
  }
}
