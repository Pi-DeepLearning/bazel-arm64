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
import com.google.common.eventbus.Subscribe;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.buildtool.buildevent.BuildCompleteEvent;
import com.google.devtools.build.lib.buildtool.buildevent.BuildInterruptedEvent;
import com.google.devtools.build.lib.buildtool.buildevent.BuildStartingEvent;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.exec.ExecutorBuilder;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.util.Preconditions;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.common.options.OptionsBase;
import java.io.IOException;

/**
 * A module that adds the WorkerActionContextProvider to the available action context providers.
 */
public class WorkerModule extends BlazeModule {
  private CommandEnvironment env;

  private WorkerFactory workerFactory;
  private WorkerPool workerPool;
  private WorkerPoolConfig workerPoolConfig;
  private WorkerOptions options;

  @Override
  public Iterable<Class<? extends OptionsBase>> getCommandOptions(Command command) {
    return "build".equals(command.name())
        ? ImmutableList.<Class<? extends OptionsBase>>of(WorkerOptions.class)
        : ImmutableList.<Class<? extends OptionsBase>>of();
  }

  @Override
  public void beforeCommand(Command command, CommandEnvironment env) {
    this.env = env;
    env.getEventBus().register(this);
  }

  @Subscribe
  public void buildStarting(BuildStartingEvent event) {
    options = event.getRequest().getOptions(WorkerOptions.class);

    if (workerFactory == null) {
      Path workerDir =
          env.getOutputBase().getRelative(env.getRuntime().getProductName() + "-workers");
      try {
        if (!workerDir.createDirectory()) {
          // Clean out old log files.
          for (Path logFile : workerDir.getDirectoryEntries()) {
            if (logFile.getBaseName().endsWith(".log")) {
              try {
                logFile.delete();
              } catch (IOException e) {
                env.getReporter()
                    .handle(Event.error("Could not delete old worker log: " + logFile));
              }
            }
          }
        }
      } catch (IOException e) {
        env.getReporter()
            .handle(Event.error("Could not create base directory for workers: " + workerDir));
      }

      workerFactory = new WorkerFactory(options, workerDir);
    }

    workerFactory.setReporter(env.getReporter());
    workerFactory.setOptions(options);

    WorkerPoolConfig newConfig = createWorkerPoolConfig(options);

    // If the config changed compared to the last run, we have to create a new pool.
    if (workerPoolConfig != null && !workerPoolConfig.equals(newConfig)) {
      shutdownPool("Worker configuration has changed, restarting worker pool...");
    }

    if (workerPool == null) {
      workerPoolConfig = newConfig;
      workerPool = new WorkerPool(workerFactory, workerPoolConfig);
    }
  }

  private WorkerPoolConfig createWorkerPoolConfig(WorkerOptions options) {
    WorkerPoolConfig config = new WorkerPoolConfig();

    // It's better to re-use a worker as often as possible and keep it hot, in order to profit
    // from JIT optimizations as much as possible.
    config.setLifo(true);

    // Keep a fixed number of workers running per key.
    config.setMaxIdlePerKey(options.workerMaxInstances);
    config.setMaxTotalPerKey(options.workerMaxInstances);
    config.setMinIdlePerKey(options.workerMaxInstances);

    // Don't limit the total number of worker processes, as otherwise the pool might be full of
    // e.g. Java workers and could never accommodate another request for a different kind of
    // worker.
    config.setMaxTotal(-1);

    // Wait for a worker to become ready when a thread needs one.
    config.setBlockWhenExhausted(true);

    // Always test the liveliness of worker processes.
    config.setTestOnBorrow(true);
    config.setTestOnCreate(true);
    config.setTestOnReturn(true);

    // No eviction of idle workers.
    config.setTimeBetweenEvictionRunsMillis(-1);

    return config;
  }

  @Override
  public void executorInit(CommandEnvironment env, BuildRequest request, ExecutorBuilder builder) {
    Preconditions.checkNotNull(workerPool);
    builder.addActionContextProvider(
        new WorkerActionContextProvider(env, request, workerPool));
    builder.addActionContextConsumer(new WorkerActionContextConsumer());
  }

  @Subscribe
  public void buildComplete(BuildCompleteEvent event) {
    if (options != null
        && options.workerQuitAfterBuild) {
      shutdownPool("Build completed, shutting down worker pool...");
    }
  }

  // Kill workers on Ctrl-C to quickly end the interrupted build.
  // TODO(philwo) - make sure that this actually *kills* the workers and not just politely waits
  // for them to finish.
  @Subscribe
  public void buildInterrupted(BuildInterruptedEvent event) {
    shutdownPool("Build interrupted, shutting down worker pool...");
  }

  /**
   * Shuts down the worker pool and sets {#code workerPool} to null.
   */
  private void shutdownPool(String reason) {
    Preconditions.checkArgument(!reason.isEmpty());

    if (workerPool != null) {
      if (options != null && options.workerVerbose) {
        env.getReporter().handle(Event.info(reason));
      }
      workerPool.close();
      workerPool = null;
    }
  }

  @Override
  public void afterCommand() {
    this.env = null;
    this.options = null;

    if (this.workerFactory != null) {
      this.workerFactory.setReporter(null);
    }
  }
}
