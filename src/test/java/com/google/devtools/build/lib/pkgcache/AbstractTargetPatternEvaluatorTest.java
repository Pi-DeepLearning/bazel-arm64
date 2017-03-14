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
package com.google.devtools.build.lib.pkgcache;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.ResolvedTargets;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.events.DelegatingEventHandler;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.ConstantRuleVisibility;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.util.PackageLoadingTestCase;
import com.google.devtools.build.lib.util.Pair;

import org.junit.Before;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Abstract framework for target pattern evaluation tests. The {@link TargetPatternEvaluatorTest}
 * contains much of the functionality that might be needed for future tests, and its methods should
 * be extracted here if they are needed by other classes.
 */
public abstract class AbstractTargetPatternEvaluatorTest extends PackageLoadingTestCase {
  protected TargetPatternEvaluator parser;
  protected RecordingParsingListener parsingListener;

  protected static ResolvedTargets<Target> parseTargetPatternList(
      TargetPatternEvaluator parser, EventHandler eventHandler,
      List<String> targetPatterns, boolean keepGoing)
          throws TargetParsingException, InterruptedException {
    return parseTargetPatternList(
        parser, eventHandler, targetPatterns, FilteringPolicies.NO_FILTER, keepGoing);
  }

  protected static ResolvedTargets<Target> parseTargetPatternList(
      TargetPatternEvaluator parser, EventHandler eventHandler,
      List<String> targetPatterns, FilteringPolicy policy,
      boolean keepGoing) throws TargetParsingException, InterruptedException {
    return parser.parseTargetPatternList(eventHandler, targetPatterns, policy, keepGoing);
  }

  /**
   * Method converts collection of targets to the new, mutable,
   * lexicographically-ordered set of corresponding labels.
   */
  protected static Set<Label> targetsToLabels(Iterable<Target> targets) {
    Set<Label> labels = new TreeSet<>();
    for (Target target : targets) {
      labels.add(target.getLabel());
    }
    return labels;
  }

  @Before
  public final void initializeParser() throws Exception {
    setUpSkyframe(ConstantRuleVisibility.PRIVATE, loadingMock.getDefaultsPackageContent());
    parser = skyframeExecutor.getPackageManager().newTargetPatternEvaluator();
    parsingListener = new RecordingParsingListener(reporter);
  }

  protected static Set<Label> labels(String... labelStrings) throws LabelSyntaxException {
    Set<Label> labels = new HashSet<>();
    for (String labelString : labelStrings) {
      labels.add(Label.parseAbsolute(labelString));
    }
    return labels;
  }

  protected Pair<Set<Label>, Boolean> parseListKeepGoing(String... patterns)
      throws TargetParsingException, InterruptedException {
    ResolvedTargets<Target> result = parseTargetPatternList(parser, parsingListener,
            Arrays.asList(patterns), true);
    return Pair.of(targetsToLabels(result.getTargets()), result.hasError());
  }

  /** Event handler that records all parsing errors. */
  protected static final class RecordingParsingListener extends DelegatingEventHandler
      implements ParseFailureListener {
    protected final List<Pair<String, String>> events = new ArrayList<>();

    private RecordingParsingListener(EventHandler delegate) {
      super(delegate);
    }

    @Override
    public void parsingError(String targetPattern, String message) {
      events.add(Pair.of(targetPattern, message));
    }

    protected void assertEmpty() {
      assertThat(events).isEmpty();
    }
  }
}
