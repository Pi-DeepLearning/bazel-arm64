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
package com.google.devtools.build.lib.runtime;

import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.util.Clock;
import com.google.devtools.build.lib.util.Preconditions;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * This class records the critical path for the graph of actions executed.
 */
@ThreadCompatible
public class AbstractCriticalPathComponent<C extends AbstractCriticalPathComponent<C>> {

  // These two fields are values of BlazeClock.nanoTime() at the relevant points in time.
  private long startNanos;
  private long finishNanos = 0;
  protected volatile boolean isRunning = true;

  /** We keep here the critical path time for the most expensive child. */
  private long childAggregatedElapsedTime = 0;

  /** The action for which we are storing the stat. */
  private final Action action;

  /**
   * Child with the maximum critical path.
   */
  @Nullable
  private C child;

  public AbstractCriticalPathComponent(Action action, long startNanos) {
    this.action = action;
    this.startNanos = startNanos;
  }

  /**
   * Record the elapsed time in case the new duration is greater. This method could be called
   * multiple times if we run shared action concurrently and the one that really gets executed takes
   * more time to send the finish event and the one that was a cache hit manages to send the event
   * before. In this case we overwrite the time with the greater time.
   *
   * <p>This logic is known to be incorrect, as other actions that depend on this action will not
   * necessarily use the correct getElapsedTimeNanos(). But we do not want to block action execution
   * because of this. So in certain conditions we might see another path as the critical path.
   */
  public synchronized boolean finishActionExecution(long startNanos, long finishNanos) {
    if (isRunning || finishNanos - startNanos > getElapsedTimeNanos()) {
      this.startNanos = startNanos;
      this.finishNanos = finishNanos;
      isRunning = false;
      return true;
    }
    return false;
  }

  /** The action for which we are storing the stat. */
  public Action getAction() {
    return action;
  }

  /**
   * Add statistics for one dependency of this action. Caller should ensure {@code dep} not
   * running.
   */
  synchronized void addDepInfo(C dep) {
    long childAggregatedWallTime = dep.getAggregatedElapsedTimeNanos();
    // Replace the child if its critical path had the maximum elapsed time.
    if (child == null || childAggregatedWallTime > this.childAggregatedElapsedTime) {
      this.childAggregatedElapsedTime = childAggregatedWallTime;
      child = dep;
    }
  }

  public long getElapsedTimeMillis() {
    return TimeUnit.NANOSECONDS.toMillis(getElapsedTimeNanos());
  }

  long getStartNanos() {
    return startNanos;
  }

  long getElapsedTimeNanos() {
    Preconditions.checkState(!isRunning, "Still running %s", action);
    return finishNanos - startNanos;
  }

  /**
   * Returns the current critical path for the action in nanoseconds.
   *
   * <p>Critical path is defined as : action_execution_time + max(child_critical_path).
   */
  public long getAggregatedElapsedTimeMillis() {
    return TimeUnit.NANOSECONDS.toMillis(getAggregatedElapsedTimeNanos());
  }

  long getAggregatedElapsedTimeNanos() {
    Preconditions.checkState(!isRunning, "Still running %s", action);
    return getElapsedTimeNanos() + childAggregatedElapsedTime;
  }

  /**
   * Get the child critical path component.
   *
   * <p>The component dependency with the maximum total critical path time.
   */
  @Nullable
  public C getChild() {
    return child;
  }

  /**
   * Returns a human readable representation of the critical path stats with all the details.
   */
  @Override
  public String toString() {
    String currentTime = "still running ";
    if (!isRunning) {
      currentTime = String.format("%.2f", getElapsedTimeMillis() / 1000.0) + "s ";
    }
    return currentTime + action.describe();
  }

  /**
   * When {@code clock} is the same {@link Clock} that was used for computing
   * {@link #relativeStartNanos}, it returns the wall time since epoch representing when
   * the action was started.
   */
  public long getStartWallTimeMillis(Clock clock) {
    long millis = clock.currentTimeMillis();
    long nanoElapsed = clock.nanoTime();
    return millis - TimeUnit.NANOSECONDS.toMillis((nanoElapsed - startNanos));
  }
}

