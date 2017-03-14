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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableMultimap.Builder;
import com.google.common.collect.Multimap;
import com.google.devtools.build.lib.actions.Executor.ActionContext;
import com.google.devtools.build.lib.actions.SpawnActionContext;
import com.google.devtools.build.lib.exec.ActionContextConsumer;

/**
 * {@link ActionContextConsumer} that requests the action contexts necessary for worker process
 * execution.
 */
public class WorkerActionContextConsumer implements ActionContextConsumer {

  @Override
  public ImmutableMap<String, String> getSpawnActionContexts() {
    return ImmutableMap.of();
  }

  @Override
  public Multimap<Class<? extends ActionContext>, String> getActionContexts() {
    Builder<Class<? extends ActionContext>, String> contexts = ImmutableMultimap.builder();
    contexts.put(SpawnActionContext.class, "standalone");
    contexts.put(SpawnActionContext.class, "worker");
    return contexts.build();
  }
}
