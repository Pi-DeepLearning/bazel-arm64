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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.packages.TestSize;
import com.google.devtools.build.lib.packages.TestTimeout;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.util.Preconditions;

import java.util.List;
import java.util.Map;

/**
 * Container for test target properties available to the
 * TestRunnerAction instance.
 */
public class TestTargetProperties {

  /**
   * Resources used by local tests of various sizes.
   *
   * <p>When changing these values, remember to update the documentation at
   * attributes/test/size.html.
   */
  private static final ResourceSet SMALL_RESOURCES = ResourceSet.create(20, 0.9, 0.00, 1);
  private static final ResourceSet MEDIUM_RESOURCES = ResourceSet.create(100, 0.9, 0.1, 1);
  private static final ResourceSet LARGE_RESOURCES = ResourceSet.create(300, 0.8, 0.1, 1);
  private static final ResourceSet ENORMOUS_RESOURCES = ResourceSet.create(800, 0.7, 0.4, 1);
  private static final ResourceSet LOCAL_TEST_JOBS_BASED_RESOURCES =
      ResourceSet.createWithLocalTestCount(1);

  private static ResourceSet getResourceSetFromSize(TestSize size) {
    switch (size) {
      case SMALL: return SMALL_RESOURCES;
      case MEDIUM: return MEDIUM_RESOURCES;
      case LARGE: return LARGE_RESOURCES;
      default: return ENORMOUS_RESOURCES;
    }
  }

  private final TestSize size;
  private final TestTimeout timeout;
  private final List<String> tags;
  private final boolean isLocal;
  private final boolean isFlaky;
  private final boolean isExternal;
  private final String language;
  private final ImmutableMap<String, String> executionInfo;

  /**
   * Creates test target properties instance. Constructor expects that it
   * will be called only for test configured targets.
   */
  TestTargetProperties(RuleContext ruleContext,
      ExecutionInfoProvider executionRequirements) {
    Rule rule = ruleContext.getRule();

    Preconditions.checkState(TargetUtils.isTestRule(rule));
    size = TestSize.getTestSize(rule);
    timeout = TestTimeout.getTestTimeout(rule);
    tags = ruleContext.attributes().get("tags", Type.STRING_LIST);
    boolean isTaggedLocal = TargetUtils.isLocalTestRule(rule)
        || TargetUtils.isExclusiveTestRule(rule);

    // We need to use method on ruleConfiguredTarget to perform validation.
    isFlaky = ruleContext.attributes().get("flaky", Type.BOOLEAN);
    isExternal = TargetUtils.isExternalTestRule(rule);

    Map<String, String> executionInfo = Maps.newLinkedHashMap();
    executionInfo.putAll(TargetUtils.getExecutionInfo(rule));
    if (isTaggedLocal) {
      executionInfo.put("local", "");
    }

    boolean isRequestedLocalByProvider = false;
    if (executionRequirements != null) {
      // This will overwrite whatever TargetUtils put there, which might be confusing.
      executionInfo.putAll(executionRequirements.getExecutionInfo());

      // We also need to mark it as local if the execution requirements specifies it.
      isRequestedLocalByProvider = executionRequirements.getExecutionInfo().containsKey("local");
    }
    this.executionInfo = ImmutableMap.copyOf(executionInfo);

    isLocal = isTaggedLocal || isRequestedLocalByProvider;

    language = TargetUtils.getRuleLanguage(rule);
  }

  public TestSize getSize() {
    return size;
  }

  public TestTimeout getTimeout() {
    return timeout;
  }

  public List<String> getTags() {
    return tags;
  }

  public boolean isLocal() {
    return isLocal;
  }

  public boolean isFlaky() {
    return isFlaky;
  }

  public boolean isExternal() {
    return isExternal;
  }

  public ResourceSet getLocalResourceUsage(boolean usingLocalTestJobs) {
    return usingLocalTestJobs
        ? LOCAL_TEST_JOBS_BASED_RESOURCES
        : TestTargetProperties.getResourceSetFromSize(size);
  }

  /**
   * Returns a map of execution info. See {@link Spawn#getExecutionInfo}.
   */
  public ImmutableMap<String, String> getExecutionInfo() {
    return executionInfo;
  }

  public String getLanguage() {
    return language;
  }
}
