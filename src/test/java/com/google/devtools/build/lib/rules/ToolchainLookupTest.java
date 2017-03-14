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
package com.google.devtools.build.lib.rules;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import org.junit.Test;

/**
 * Unit tests for the {@code toolchain_lookup} rule.
 */
public class ToolchainLookupTest extends BuildViewTestCase {
  @Test
  public void testSmoke() throws Exception {
    ConfiguredTarget cc = getConfiguredTarget(getRuleClassProvider().getToolsRepository()
        + "//tools/cpp:lookup");
    assertThat(cc.getProvider(ToolchainProvider.class).getMakeVariables())
        .containsKey("TARGET_CPU");
  }
}
