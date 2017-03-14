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
package com.google.devtools.build.lib.exec;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.ActionInputFileCache;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.Executor.ActionContext;
import com.google.devtools.build.lib.util.Preconditions;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Builder class to create an {@link Executor} instance. This class is part of the module API,
 * which allows modules to affect how the executor is initialized.
 */
public class ExecutorBuilder {
  private final List<ActionContextProvider> actionContextProviders = new ArrayList<>();
  private final List<ActionContextConsumer> actionContextConsumers = new ArrayList<>();
  private ActionInputFileCache cache;
  private ActionInputPrefetcher prefetcher;

  // These methods shouldn't be public, but they have to be right now as ExecutionTool is in another
  // package.
  public ImmutableList<ActionContextProvider> getActionContextProviders() {
    return ImmutableList.copyOf(actionContextProviders);
  }

  public ImmutableList<ActionContextConsumer> getActionContextConsumers() {
    return ImmutableList.copyOf(actionContextConsumers);
  }

  @Nullable
  public ActionInputFileCache getActionInputFileCache() {
    return cache;
  }

  public ActionInputPrefetcher getActionInputPrefetcher() {
    return prefetcher == null ? ActionInputPrefetcher.NONE : prefetcher;
  }

  /**
   * Adds the specified action context providers to the executor.
   */
  public ExecutorBuilder addActionContextProvider(ActionContextProvider provider) {
    this.actionContextProviders.add(provider);
    return this;
  }

  /**
   * Adds the specified action context to the executor, by wrapping it in a simple action context
   * provider implementation.
   */
  public ExecutorBuilder addActionContext(ActionContext context) {
    return addActionContextProvider(new SimpleActionContextProvider(context));
  }

  /**
   * Adds the specified action context consumer to the executor.
   */
  public ExecutorBuilder addActionContextConsumer(ActionContextConsumer consumer) {
    this.actionContextConsumers.add(consumer);
    return this;
  }

  /**
   * Sets the cache for action input files. Only one module may set the cache. If multiple modules
   * set it, this method will throw an {@link IllegalStateException}.
   */
  public ExecutorBuilder setActionInputFileCache(ActionInputFileCache cache) {
    Preconditions.checkState(this.cache == null);
    this.cache = Preconditions.checkNotNull(cache);
    return this;
  }

  /**
   * Sets the action input prefetcher. Only one module may set the prefetcher. If multiple modules
   * set it, this method will throw an {@link IllegalStateException}.
   */
  public ExecutorBuilder setActionInputPrefetcher(ActionInputPrefetcher prefetcher) {
    Preconditions.checkState(this.prefetcher == null);
    this.prefetcher = Preconditions.checkNotNull(prefetcher);
    return this;
  }
}
