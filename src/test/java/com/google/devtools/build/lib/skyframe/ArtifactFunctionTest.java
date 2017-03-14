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
package com.google.devtools.build.lib.skyframe;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.skyframe.FileArtifactValue.create;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.testing.EqualsTester;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata.MiddlemanType;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifactType;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.MissingInputFileException;
import com.google.devtools.build.lib.actions.Root;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.actions.util.TestAction.DummyAction;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link ArtifactFunction}.
 */
// Doesn't actually need any particular Skyframe, but is only relevant to Skyframe full mode.
@RunWith(JUnit4.class)
public class ArtifactFunctionTest extends ArtifactFunctionTestCase {

  private PathFragment allowedMissingInput = null;

  @Before
  public final void setUp() throws Exception  {
    delegateActionExecutionFunction = new SimpleActionExecutionFunction();
    allowedMissingInputsPredicate = new Predicate<PathFragment>() {
      @Override
      public boolean apply(PathFragment input) {
        return input.equals(allowedMissingInput);
      }
    };
  }

  private void assertFileArtifactValueMatches(boolean expectDigest) throws Throwable {
    Artifact output = createDerivedArtifact("output");
    Path path = output.getPath();
    file(path, "contents");
    assertValueMatches(path.stat(), expectDigest ? path.getMD5Digest() : null, evaluateFAN(output));
  }

  @Test
  public void testBasicArtifact() throws Throwable {
    fastDigest = false;
    assertFileArtifactValueMatches(/*expectDigest=*/ true);
  }

  @Test
  public void testBasicArtifactWithXattr() throws Throwable {
    fastDigest = true;
    assertFileArtifactValueMatches(/*expectDigest=*/ true);
  }

  @Test
  public void testMissingNonMandatoryArtifact() throws Throwable {
    Artifact input = createSourceArtifact("input1");
    assertNotNull(evaluateArtifactValue(input, /*mandatory=*/ false));
  }

  @Test
  public void testMissingMandatoryAllowedMissingArtifact() throws Throwable {
    Artifact input = createSourceArtifact("allowedMissing");
    allowedMissingInput = input.getRootRelativePath();
    assertThat(evaluateArtifactValue(input, /*mandatory=*/ true))
        .isEqualTo(FileArtifactValue.MISSING_FILE_MARKER);
  }

  @Test
  public void testUnreadableMandatoryAllowedMissingArtifact() throws Throwable {
    Artifact input = createSourceArtifact("allowedMissing");
    file(input.getPath(), "allowedMissing");
    input.getPath().chmod(0);

    allowedMissingInput = input.getRootRelativePath();
    assertThat(evaluateArtifactValue(input, /*mandatory=*/ true))
        .isEqualTo(FileArtifactValue.MISSING_FILE_MARKER);
  }

  @Test
  public void testUnreadableInputWithFsWithAvailableDigest() throws Throwable {
    final byte[] expectedDigest = MessageDigest.getInstance("md5").digest(
        "someunreadablecontent".getBytes(StandardCharsets.UTF_8));
    setupRoot(
        new CustomInMemoryFs() {
          @Override
          public byte[] getMD5Digest(Path path) throws IOException {
            return path.getBaseName().equals("unreadable")
                ? expectedDigest
                : super.getMD5Digest(path);
          }
        });

    Artifact input = createSourceArtifact("unreadable");
    Path inputPath = input.getPath();
    file(inputPath, "dummynotused");
    inputPath.chmod(0);

    FileArtifactValue value =
        (FileArtifactValue) evaluateArtifactValue(input, /*mandatory=*/ true);

    FileStatus stat = inputPath.stat();
    assertThat(value.getSize()).isEqualTo(stat.getSize());
    assertThat(value.getDigest()).isEqualTo(expectedDigest);
  }

  @Test
  public void testMissingMandatoryArtifact() throws Throwable {
    Artifact input = createSourceArtifact("input1");
    try {
      evaluateArtifactValue(input, /*mandatory=*/ true);
      fail();
    } catch (MissingInputFileException ex) {
      // Expected.
    }
  }

  @Test
  public void testMiddlemanArtifact() throws Throwable {
    Artifact output = createDerivedArtifact("output");
    Artifact input1 = createSourceArtifact("input1");
    Artifact input2 = createDerivedArtifact("input2");
    Action action =
        new DummyAction(
            ImmutableList.of(input1, input2), output, MiddlemanType.AGGREGATING_MIDDLEMAN);
    // Overwrite default generating action with this one.
    for (Iterator<ActionAnalysisMetadata> it = actions.iterator(); it.hasNext(); ) {
      if (it.next().getOutputs().contains(output)) {
        it.remove();
        break;
      }
    }
    actions.add(action);
    file(input2.getPath(), "contents");
    file(input1.getPath(), "source contents");
    evaluate(
        Iterables.toArray(
            ArtifactSkyKey.mandatoryKeys(ImmutableSet.of(input2, input1, input2)), SkyKey.class));
    SkyValue value = evaluateArtifactValue(output);
    assertThat(((AggregatingArtifactValue) value).getInputs())
        .containsExactly(Pair.of(input1, create(input1)), Pair.of(input2, create(input2)));
  }

  @Test
  public void testIOException() throws Exception {
    fastDigest = false;
    final IOException exception = new IOException("beep");
    setupRoot(
        new CustomInMemoryFs() {
          @Override
          public byte[] getMD5Digest(Path path) throws IOException {
            throw exception;
          }
        });
    Artifact artifact = createDerivedArtifact("no-read");
    writeFile(artifact.getPath(), "content");
    try {
      create(createDerivedArtifact("no-read"));
      fail();
    } catch (IOException e) {
      assertSame(exception, e);
    }
  }

  /**
   * Tests that ArtifactFunction rethrows transitive {@link IOException}s as
   * {@link MissingInputFileException}s.
   */
  @Test
  public void testIOException_EndToEnd() throws Throwable {
    final IOException exception = new IOException("beep");
    setupRoot(
        new CustomInMemoryFs() {
          @Override
          public FileStatus stat(Path path, boolean followSymlinks) throws IOException {
            if (path.getBaseName().equals("bad")) {
              throw exception;
            }
            return super.stat(path, followSymlinks);
          }
        });
    try {
      evaluateArtifactValue(createSourceArtifact("bad"));
      fail();
    } catch (MissingInputFileException e) {
      assertThat(e.getMessage()).contains(exception.getMessage());
    }
  }

  @Test
  public void testNoMtimeIfNonemptyFile() throws Exception {
    Artifact artifact = createDerivedArtifact("no-digest");
    Path path = artifact.getPath();
    writeFile(path, "hello"); //Non-empty file.
    FileArtifactValue value = create(artifact);
    assertArrayEquals(path.getMD5Digest(), value.getDigest());
    try {
      value.getModifiedTime();
      fail("mtime for non-empty file should not be stored.");
    } catch (UnsupportedOperationException e) {
      // Expected.
    }
  }

  @Test
  public void testDirectory() throws Exception {
    Artifact artifact = createDerivedArtifact("dir");
    Path path = artifact.getPath();
    FileSystemUtils.createDirectoryAndParents(path);
    path.setLastModifiedTime(1L);
    FileArtifactValue value = create(artifact);
    assertNull(value.getDigest());
    assertEquals(1L, value.getModifiedTime());
  }

  // Empty files are the same as normal files -- mtime is not stored.
  @Test
  public void testEmptyFile() throws Exception {
    Artifact artifact = createDerivedArtifact("empty");
    Path path = artifact.getPath();
    writeFile(path, "");
    path.setLastModifiedTime(1L);
    FileArtifactValue value = create(artifact);
    assertArrayEquals(path.getMD5Digest(), value.getDigest());
    assertEquals(0L, value.getSize());
  }

  @Test
  public void testEquality() throws Exception {
    Artifact artifact1 = createDerivedArtifact("artifact1");
    Artifact artifact2 = createDerivedArtifact("artifact2");
    Artifact diffDigest = createDerivedArtifact("diffDigest");
    Artifact diffMtime = createDerivedArtifact("diffMtime");
    Artifact empty1 = createDerivedArtifact("empty1");
    Artifact empty2 = createDerivedArtifact("empty2");
    Artifact empty3 = createDerivedArtifact("empty3");
    Artifact dir1 = createDerivedArtifact("dir1");
    Artifact dir2 = createDerivedArtifact("dir2");
    Artifact dir3 = createDerivedArtifact("dir3");
    Path path1 = artifact1.getPath();
    Path path2 = artifact2.getPath();
    Path digestPath = diffDigest.getPath();
    Path mtimePath = diffMtime.getPath();
    writeFile(artifact1.getPath(), "content");
    writeFile(artifact2.getPath(), "content");
    path1.setLastModifiedTime(0);
    path2.setLastModifiedTime(0);
    writeFile(diffDigest.getPath(), "1234567"); // Same size as artifact1.
    digestPath.setLastModifiedTime(0);
    writeFile(mtimePath, "content");
    mtimePath.setLastModifiedTime(1);
    Path emptyPath1 = empty1.getPath();
    Path emptyPath2 = empty2.getPath();
    Path emptyPath3 = empty3.getPath();
    writeFile(emptyPath1, "");
    writeFile(emptyPath2, "");
    writeFile(emptyPath3, "");
    emptyPath1.setLastModifiedTime(0L);
    emptyPath2.setLastModifiedTime(1L);
    emptyPath3.setLastModifiedTime(1L);
    Path dirPath1 = dir1.getPath();
    Path dirPath2 = dir2.getPath();
    Path dirPath3 = dir3.getPath();
    FileSystemUtils.createDirectoryAndParents(dirPath1);
    FileSystemUtils.createDirectoryAndParents(dirPath2);
    FileSystemUtils.createDirectoryAndParents(dirPath3);
    dirPath1.setLastModifiedTime(0L);
    dirPath2.setLastModifiedTime(1L);
    dirPath3.setLastModifiedTime(1L);
    EqualsTester equalsTester = new EqualsTester();
    equalsTester
        .addEqualityGroup(create(artifact1), create(artifact2), create(diffMtime))
        .addEqualityGroup(create(empty1), create(empty2), create(empty3))
        .addEqualityGroup(create(dir1))
        .addEqualityGroup(create(dir2), create(dir3))
        .testEquals();
  }

  @Test
  public void testActionTreeArtifactOutput() throws Throwable {
    Artifact artifact = createDerivedTreeArtifactWithAction("treeArtifact");
    TreeFileArtifact treeFileArtifact1 = createFakeTreeFileArtifact(artifact, "child1", "hello1");
    TreeFileArtifact treeFileArtifact2 = createFakeTreeFileArtifact(artifact, "child2", "hello2");

    TreeArtifactValue value = (TreeArtifactValue) evaluateArtifactValue(artifact);
    assertNotNull(value.getChildValues().get(treeFileArtifact1));
    assertNotNull(value.getChildValues().get(treeFileArtifact2));
    assertNotNull(value.getChildValues().get(treeFileArtifact1).getDigest());
    assertNotNull(value.getChildValues().get(treeFileArtifact2).getDigest());
  }

  @Test
  public void testSpawnActionTemplate() throws Throwable {
    // artifact1 is a tree artifact generated by normal action.
    Artifact artifact1 = createDerivedTreeArtifactWithAction("treeArtifact1");
    createFakeTreeFileArtifact(artifact1, "child1", "hello1");
    createFakeTreeFileArtifact(artifact1, "child2", "hello2");


    // artifact2 is a tree artifact generated by action template.
    Artifact artifact2 = createDerivedTreeArtifactOnly("treeArtifact2");
    TreeFileArtifact treeFileArtifact1 = createFakeTreeFileArtifact(artifact2, "child1", "hello1");
    TreeFileArtifact treeFileArtifact2 = createFakeTreeFileArtifact(artifact2, "child2", "hello2");

    actions.add(
        ActionsTestUtil.createDummySpawnActionTemplate(artifact1, artifact2));

    TreeArtifactValue value = (TreeArtifactValue) evaluateArtifactValue(artifact2);
    assertNotNull(value.getChildValues().get(treeFileArtifact1));
    assertNotNull(value.getChildValues().get(treeFileArtifact2));
    assertNotNull(value.getChildValues().get(treeFileArtifact1).getDigest());
    assertNotNull(value.getChildValues().get(treeFileArtifact2).getDigest());
  }

  @Test
  public void testConsecutiveSpawnActionTemplates() throws Throwable {
    // artifact1 is a tree artifact generated by normal action.
    Artifact artifact1 = createDerivedTreeArtifactWithAction("treeArtifact1");
    createFakeTreeFileArtifact(artifact1, "child1", "hello1");
    createFakeTreeFileArtifact(artifact1, "child2", "hello2");

    // artifact2 is a tree artifact generated by action template.
    Artifact artifact2 = createDerivedTreeArtifactOnly("treeArtifact2");
    createFakeTreeFileArtifact(artifact2, "child1", "hello1");
    createFakeTreeFileArtifact(artifact2, "child2", "hello2");
    actions.add(
        ActionsTestUtil.createDummySpawnActionTemplate(artifact1, artifact2));

    // artifact3 is a tree artifact generated by action template.
    Artifact artifact3 = createDerivedTreeArtifactOnly("treeArtifact3");
    TreeFileArtifact treeFileArtifact1 = createFakeTreeFileArtifact(artifact3, "child1", "hello1");
    TreeFileArtifact treeFileArtifact2 = createFakeTreeFileArtifact(artifact3, "child2", "hello2");
    actions.add(
        ActionsTestUtil.createDummySpawnActionTemplate(artifact2, artifact3));

    TreeArtifactValue value = (TreeArtifactValue) evaluateArtifactValue(artifact3);
    assertNotNull(value.getChildValues().get(treeFileArtifact1));
    assertNotNull(value.getChildValues().get(treeFileArtifact2));
    assertNotNull(value.getChildValues().get(treeFileArtifact1).getDigest());
    assertNotNull(value.getChildValues().get(treeFileArtifact2).getDigest());
  }

  private void file(Path path, String contents) throws Exception {
    FileSystemUtils.createDirectoryAndParents(path.getParentDirectory());
    writeFile(path, contents);
  }

  private Artifact createSourceArtifact(String path) {
    return new Artifact(new PathFragment(path), Root.asSourceRoot(root));
  }

  private Artifact createDerivedArtifact(String path) {
    PathFragment execPath = new PathFragment("out").getRelative(path);
    Path fullPath = root.getRelative(execPath);
    Artifact output =
        new Artifact(
            fullPath, Root.asDerivedRoot(root, root.getRelative("out")), execPath, ALL_OWNER);
    actions.add(new DummyAction(ImmutableList.<Artifact>of(), output));
    return output;
  }

  private Artifact createDerivedTreeArtifactWithAction(String path) {
    Artifact treeArtifact = createDerivedTreeArtifactOnly(path);
    actions.add(new DummyAction(ImmutableList.<Artifact>of(), treeArtifact));
    return treeArtifact;
  }

  private Artifact createDerivedTreeArtifactOnly(String path) {
    PathFragment execPath = new PathFragment("out").getRelative(path);
    Path fullPath = root.getRelative(execPath);
    return new SpecialArtifact(
        fullPath,
        Root.asDerivedRoot(root, root.getRelative("out")),
        execPath,
        ALL_OWNER,
        SpecialArtifactType.TREE);
  }

  private TreeFileArtifact createFakeTreeFileArtifact(Artifact treeArtifact,
      String parentRelativePath, String content) throws Exception {
    TreeFileArtifact treeFileArtifact = ActionInputHelper.treeFileArtifact(
        treeArtifact, new PathFragment(parentRelativePath));
    Path path = treeFileArtifact.getPath();
    FileSystemUtils.createDirectoryAndParents(path.getParentDirectory());
    writeFile(path, content);
    return treeFileArtifact;
  }

  private void assertValueMatches(FileStatus file, byte[] digest, FileArtifactValue value)
      throws IOException {
    assertEquals(file.getSize(), value.getSize());
    if (digest == null) {
      assertNull(value.getDigest());
      assertEquals(file.getLastModifiedTime(), value.getModifiedTime());
    } else {
      assertArrayEquals(digest, value.getDigest());
    }
  }

  private FileArtifactValue evaluateFAN(Artifact artifact) throws Throwable {
    return ((FileArtifactValue) evaluateArtifactValue(artifact));
  }

  private SkyValue evaluateArtifactValue(Artifact artifact) throws Throwable {
    return evaluateArtifactValue(artifact, /*isMandatory=*/ true);
  }

  private SkyValue evaluateArtifactValue(Artifact artifact, boolean mandatory) throws Throwable {
    SkyKey key = ArtifactSkyKey.key(artifact, mandatory);
    EvaluationResult<SkyValue> result = evaluate(ImmutableList.of(key).toArray(new SkyKey[0]));
    if (result.hasError()) {
      throw result.getError().getException();
    }
    return result.get(key);
  }

  private void setGeneratingActions() {
    if (evaluator.getExistingValueForTesting(OWNER_KEY) == null) {
      differencer.inject(ImmutableMap.of(
          OWNER_KEY,
          new ActionLookupValue(ImmutableList.<ActionAnalysisMetadata>copyOf(actions))));
    }
  }

  private <E extends SkyValue> EvaluationResult<E> evaluate(SkyKey... keys)
      throws InterruptedException {
    setGeneratingActions();
    return driver.evaluate(
        Arrays.asList(keys),
        /*keepGoing=*/false,
        SkyframeExecutor.DEFAULT_THREAD_COUNT,
        NullEventHandler.INSTANCE);
  }

  /** Value Builder for actions that just stats and stores the output file (which must exist). */
  private static class SimpleActionExecutionFunction implements SkyFunction {
    @Override
    public SkyValue compute(SkyKey skyKey, Environment env) {
      Map<Artifact, FileValue> artifactData = new HashMap<>();
      Map<Artifact, TreeArtifactValue> treeArtifactData = new HashMap<>();
      Map<Artifact, FileArtifactValue> additionalOutputData = new HashMap<>();
      Action action = (Action) skyKey.argument();
      Artifact output = Iterables.getOnlyElement(action.getOutputs());

      try {
        if (output.isTreeArtifact()) {
          TreeFileArtifact treeFileArtifact1 = ActionInputHelper.treeFileArtifact(
              output, new PathFragment("child1"));
          TreeFileArtifact treeFileArtifact2 = ActionInputHelper.treeFileArtifact(
              output, new PathFragment("child2"));
          TreeArtifactValue treeArtifactValue = TreeArtifactValue.create(ImmutableMap.of(
              treeFileArtifact1, FileArtifactValue.create(treeFileArtifact1),
              treeFileArtifact2, FileArtifactValue.create(treeFileArtifact2)));
          treeArtifactData.put(output, treeArtifactValue);
        } else if (action.getActionType() == MiddlemanType.NORMAL) {
          FileValue fileValue = ActionMetadataHandler.fileValueFromArtifact(output, null, null);
          artifactData.put(output, fileValue);
          additionalOutputData.put(output, FileArtifactValue.create(output, fileValue));
       } else {
          additionalOutputData.put(output, FileArtifactValue.DEFAULT_MIDDLEMAN);
        }
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
      return new ActionExecutionValue(
          artifactData,
          treeArtifactData,
          additionalOutputData);
    }

    @Override
    public String extractTag(SkyKey skyKey) {
      return null;
    }
  }
}
