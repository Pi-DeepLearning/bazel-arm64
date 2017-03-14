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
package com.google.devtools.build.lib.analysis.actions;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.NULL_ACTION_OWNER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.analysis.util.ActionTester;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.exec.util.TestExecutorBuilder;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import java.util.Collection;
import org.junit.Before;

public abstract class FileWriteActionTestCase extends BuildViewTestCase {

  private Action action;
  private Artifact outputArtifact;
  private Path output;
  private Executor executor;
  private ActionExecutionContext context;

  @Before
  public final void createAction() throws Exception {
    outputArtifact = getBinArtifactWithNoOwner("destination.txt");
    output = outputArtifact.getPath();
    FileSystemUtils.createDirectoryAndParents(output.getParentDirectory());
    action = createAction(NULL_ACTION_OWNER, outputArtifact, "Hello World", false);
  }

  @Before
  public final void createExecutorAndContext() throws Exception {
    executor = new TestExecutorBuilder(directories, binTools).build();
    context = new ActionExecutionContext(executor, null, null, new FileOutErr(),
          ImmutableMap.<String, String>of(), null);
  }

  protected abstract Action createAction(
      ActionOwner actionOwner, Artifact outputArtifact, String data, boolean makeExecutable);

  protected void checkNoInputsByDefault() {
    assertThat(action.getInputs()).isEmpty();
    assertNull(action.getPrimaryInput());
  }

  protected void checkDestinationArtifactIsOutput() {
    Collection<Artifact> outputs = action.getOutputs();
    assertEquals(Sets.newHashSet(outputArtifact), Sets.newHashSet(outputs));
    assertEquals(outputArtifact, action.getPrimaryOutput());
  }

  protected void checkCanWriteNonExecutableFile() throws Exception {
    action.execute(context);
    String content = new String(FileSystemUtils.readContentAsLatin1(output));
    assertEquals("Hello World", content);
    assertFalse(output.isExecutable());
  }

  protected void checkCanWriteExecutableFile() throws Exception {
    Artifact outputArtifact = getBinArtifactWithNoOwner("hello");
    Path output = outputArtifact.getPath();
    Action action = createAction(NULL_ACTION_OWNER, outputArtifact, "echo 'Hello World'", true);
    action.execute(context);
    String content = new String(FileSystemUtils.readContentAsLatin1(output));
    assertEquals("echo 'Hello World'", content);
    assertTrue(output.isExecutable());
  }

  protected void checkComputesConsistentKeys() throws Exception {
    ActionTester.runTest(4, new ActionTester.ActionCombinationFactory() {
      @Override
      public Action generate(int i) {
        return createAction(NULL_ACTION_OWNER, outputArtifact,
            (i & 1) == 0 ? "0" : "1",
            (i & 2) == 0);
      }
    });
  }
}
