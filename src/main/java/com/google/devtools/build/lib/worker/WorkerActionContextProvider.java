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
package com.google.devtools.build.lib.worker;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.devtools.build.lib.actions.Executor.ActionContext;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.exec.ActionContextProvider;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.rules.test.TestActionContext;
import com.google.devtools.build.lib.runtime.CommandEnvironment;

/**
 * Factory for the Worker-based execution strategy.
 */
final class WorkerActionContextProvider extends ActionContextProvider {
  private final ImmutableList<ActionContext> strategies;

  public WorkerActionContextProvider(
      CommandEnvironment env, BuildRequest buildRequest, WorkerPool workers) {
    int maxRetries = buildRequest.getOptions(WorkerOptions.class).workerMaxRetries;
    ImmutableMultimap.Builder<String, String> extraFlags = ImmutableMultimap.builder();
    extraFlags.putAll(buildRequest.getOptions(WorkerOptions.class).workerExtraFlags);

    WorkerSpawnStrategy workerSpawnStrategy =
        new WorkerSpawnStrategy(
            env.getDirectories(),
            workers,
            buildRequest.getOptions(ExecutionOptions.class).verboseFailures,
            maxRetries,
            buildRequest.getOptions(WorkerOptions.class).workerVerbose,
            extraFlags.build());
    TestActionContext workerTestStrategy =
        new WorkerTestStrategy(env, buildRequest, workers, maxRetries, extraFlags.build());
    this.strategies = ImmutableList.of(workerSpawnStrategy, workerTestStrategy);
  }

  @Override
  public Iterable<ActionContext> getActionContexts() {
    return strategies;
  }
}
