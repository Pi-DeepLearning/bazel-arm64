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
import static com.google.devtools.build.skyframe.WalkableGraphUtils.exists;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.util.Preconditions;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.WalkableGraph;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link PrepareDepsOfPatternsFunction}. */
@RunWith(JUnit4.class)
public class PrepareDepsOfPatternsFunctionSmartNegationTest extends BuildViewTestCase {

  private static SkyKey getKeyForLabel(Label label) {
    // Note that these tests used to look for TargetMarker SkyKeys before TargetMarker was
    // inlined in TransitiveTraversalFunction. Because TargetMarker is now inlined, it doesn't
    // appear in the graph. Instead, these tests now look for TransitiveTraversal keys.
    return TransitiveTraversalValue.key(label);
  }

  @Test
  public void testRecursiveEvaluationFailsOnBadBuildFile() throws Exception {
    // Given a well-formed package "@//foo" and a malformed package "@//foo/foo",
    createFooAndFooFoo();

    // Given a target pattern sequence consisting of a recursive pattern for "//foo/...",
    ImmutableList<String> patternSequence = ImmutableList.of("//foo/...");

    // When PrepareDepsOfPatternsFunction completes evaluation (with no error because it was
    // recovered from),
    WalkableGraph walkableGraph =
        getGraphFromPatternsEvaluation(
            patternSequence, /*successExpected=*/ true, /*keepGoing=*/ true);

    // Then the graph contains package values for "@//foo" and "@//foo/foo",
    assertTrue(exists(PackageValue.key(PackageIdentifier.parse("@//foo")), walkableGraph));
    assertTrue(exists(PackageValue.key(PackageIdentifier.parse("@//foo/foo")), walkableGraph));

    // But the graph does not contain a value for the target "@//foo/foo:foofoo".
    assertFalse(exists(getKeyForLabel(Label.create("@//foo/foo", "foofoo")), walkableGraph));
  }

  @Test
  public void testNegativePatternBlocksPatternEvaluation() throws Exception {
    // Given a well-formed package "//foo" and a malformed package "//foo/foo",
    createFooAndFooFoo();

    // Given a target pattern sequence consisting of a recursive pattern for "//foo/..." followed
    // by a negative pattern for the malformed package,
    ImmutableList<String> patternSequence = ImmutableList.of("//foo/...", "-//foo/foo/...");

    assertSkipsFoo(patternSequence);
  }

  @Test
  public void testBlacklistPatternBlocksPatternEvaluation() throws Exception {
    // Given a well-formed package "//foo" and a malformed package "//foo/foo",
    createFooAndFooFoo();

    // Given a target pattern sequence consisting of a recursive pattern for "//foo/...",
    ImmutableList<String> patternSequence = ImmutableList.of("//foo/...");

    // and a blacklist for the malformed package,
    getSkyframeExecutor().setBlacklistedPackagePrefixesFile(
        new PathFragment("config/blacklist.txt"));
    scratch.file("config/blacklist.txt", "foo/foo");

    assertSkipsFoo(patternSequence);
  }

  private void assertSkipsFoo(ImmutableList<String> patternSequence) throws Exception {


    // When PrepareDepsOfPatternsFunction completes evaluation (successfully),
    WalkableGraph walkableGraph =
        getGraphFromPatternsEvaluation(
            patternSequence, /*successExpected=*/ true, /*keepGoing=*/ true);

    // Then the graph contains a package value for "@//foo",
    assertTrue(exists(PackageValue.key(PackageIdentifier.parse("@//foo")), walkableGraph));

    // But no package value for "@//foo/foo",
    assertFalse(exists(PackageValue.key(PackageIdentifier.parse("@//foo/foo")), walkableGraph));

    // And the graph does not contain a value for the target "@//foo/foo:foofoo".
    Label label = Label.create("@//foo/foo", "foofoo");
    assertFalse(exists(getKeyForLabel(label), walkableGraph));
  }

  @Test
  public void testNegativeNonTBDPatternsAreSkippedWithWarnings() throws Exception {
    // Given a target pattern sequence with a negative non-TBD pattern,
    ImmutableList<String> patternSequence = ImmutableList.of("-//foo/bar");

    // When PrepareDepsOfPatternsFunction completes evaluation,
    getGraphFromPatternsEvaluation(patternSequence, /*successExpected=*/ true, /*keepGoing=*/ true);

    // Then a event is published that says that negative non-TBD patterns are skipped.
    assertContainsEvent(
        "Skipping '-//foo/bar': Negative target patterns of types other than \"targets below "
            + "directory\" are not permitted.");
  }

  // Helpers:

  private WalkableGraph getGraphFromPatternsEvaluation(
      ImmutableList<String> patternSequence, boolean successExpected, boolean keepGoing)
      throws InterruptedException {
    SkyKey independentTarget = PrepareDepsOfPatternsValue.key(patternSequence, "");
    ImmutableList<SkyKey> singletonTargetPattern = ImmutableList.of(independentTarget);

    // When PrepareDepsOfPatternsFunction completes evaluation,
    EvaluationResult<SkyValue> evaluationResult =
        getSkyframeExecutor()
            .getDriverForTesting()
            .evaluate(singletonTargetPattern, keepGoing, LOADING_PHASE_THREADS, eventCollector);
    // The evaluation has no errors if success was expected.
    assertThat(evaluationResult.hasError()).isNotEqualTo(successExpected);
    return Preconditions.checkNotNull(evaluationResult.getWalkableGraph());
  }

  private void createFooAndFooFoo() throws IOException {
    scratch.file(
        "foo/BUILD", "genrule(name = 'foo',", "    outs = ['out.txt'],", "    cmd = 'touch $@')");
    scratch.file(
        "foo/foo/BUILD", "genrule(name = 'foofoo',", "    This isn't even remotely grammatical.)");
  }
}
