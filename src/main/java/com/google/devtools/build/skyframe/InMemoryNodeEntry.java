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
package com.google.devtools.build.skyframe;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.util.GroupedList;
import com.google.devtools.build.lib.util.GroupedList.GroupedListHelper;
import com.google.devtools.build.lib.util.Preconditions;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * In-memory implementation of {@link NodeEntry}. All operations on this class are thread-safe.
 *
 * <p>Care was taken to provide certain compound operations to avoid certain check-then-act races.
 * That means this class is somewhat closely tied to the exact Evaluator implementation.
 *
 * <p>Consider the example with two threads working on two nodes, where one depends on the other,
 * say b depends on a. If a completes first, it's done. If it completes second, it needs to signal
 * b, and potentially re-schedule it. If b completes first, it must exit, because it will be
 * signaled (and re-scheduled) by a. If it completes second, it must signal (and re-schedule)
 * itself. However, if the Evaluator supported re-entrancy for a node, then this wouldn't have to
 * be so strict, because duplicate scheduling would be less problematic.
 *
 * <p>The transient state of an {@code InMemoryNodeEntry} is kept in a {@link BuildingState} object.
 * Many of the methods of {@code InMemoryNodeEntry} are just wrappers around the corresponding
 * {@link BuildingState} methods.
 *
 * <p>This class is public only for the benefit of alternative graph implementations outside of the
 * package.
 */
public class InMemoryNodeEntry implements NodeEntry {

  /** Actual data stored in this entry when it is done. */
  private SkyValue value = null;

  /**
   * The last version of the graph at which this node's value was changed. In {@link #setValue} it
   * may be determined that the value being written to the graph at a given version is the same as
   * the already-stored value. In that case, the version will remain the same. The version can be
   * thought of as the latest timestamp at which this value was changed.
   */
  protected Version lastChangedVersion = MinimalVersion.INSTANCE;

  /**
   * Returns the last version this entry was evaluated at, even if it re-evaluated to the same
   * value. When a child signals this entry with the last version it was changed at in
   * {@link #signalDep}, this entry need not re-evaluate if the child's version is at most this
   * version, even if the {@link #lastChangedVersion} is less than this one.
   *
   * @see #signalDep(Version)
   */
  protected Version lastEvaluatedVersion = MinimalVersion.INSTANCE;

  /**
   * This object represents the direct deps of the node, in groups if the {@code SkyFunction}
   * requested them that way. It contains either the in-progress direct deps, stored as a
   * {@code GroupedList<SkyKey>} before the node is finished building, or the full direct deps,
   * compressed in a memory-efficient way (via {@link GroupedList#compress}, after the node is done.
   *
   * <p>It is initialized lazily in getTemporaryDirectDeps() to save a little bit more memory.
   */
  private Object directDeps = null;

  /**
   * This list stores the reverse dependencies of this node that have been declared so far.
   *
   * <p>In case of a single object we store the object unwrapped, without the list, for
   * memory-efficiency.
   */
  protected Object reverseDeps = ImmutableList.of();

  /**
   * When reverse deps are removed, checked for presence, or possibly added, we store them in this
   * object instead of directly doing the operation. That is because removals/checks in reverseDeps
   * are O(N). Originally reverseDeps was a HashSet, but because of memory consumption we switched
   * to a list.
   *
   * <p>Internally, ReverseDepsUtilImpl consolidates this data periodically, and when the set of
   * reverse deps is requested. While this operation is not free, it can be done more effectively
   * than trying to remove/check each dirty reverse dependency individually (O(N) each time).
   */
  private List<Object> reverseDepsDataToConsolidate = null;

  private static final ReverseDepsUtil<InMemoryNodeEntry> REVERSE_DEPS_UTIL =
      new ReverseDepsUtilImpl<InMemoryNodeEntry>() {
        @Override
        void setReverseDepsObject(InMemoryNodeEntry container, Object object) {
          container.reverseDeps = object;
        }

        @Override
        void setDataToConsolidate(InMemoryNodeEntry container, List<Object> dataToConsolidate) {
          container.reverseDepsDataToConsolidate = dataToConsolidate;
        }

        @Override
        Object getReverseDepsObject(InMemoryNodeEntry container) {
          return container.reverseDeps;
        }

        @Override
        List<Object> getDataToConsolidate(InMemoryNodeEntry container) {
          return container.reverseDepsDataToConsolidate;
        }
      };

  /**
   * The transient state of this entry, after it has been created but before it is done. It allows
   * us to keep the current state of the entry across invalidation and successive evaluations.
   */
  @VisibleForTesting @Nullable protected volatile BuildingState buildingState = new BuildingState();

  /**
   * Construct a InMemoryNodeEntry. Use ONLY in Skyframe evaluation and graph implementations.
   */
  public InMemoryNodeEntry() {
  }

  @Override
  public boolean keepEdges() {
    return true;
  }

  @Override
  public boolean isDone() {
    return buildingState == null;
  }

  @Override
  public synchronized SkyValue getValue() {
    Preconditions.checkState(isDone(), "no value until done. ValueEntry: %s", this);
    return ValueWithMetadata.justValue(value);
  }

  @Override
  public synchronized SkyValue getValueMaybeWithMetadata() {
    Preconditions.checkState(isDone(), "no value until done: %s", this);
    return value;
  }

  @Override
  public synchronized SkyValue toValue() {
    if (isDone()) {
      return getErrorInfo() == null ? getValue() : null;
    } else if (isChanged() || isDirty()) {
      SkyValue lastBuildValue = null;
      try {
        lastBuildValue = getDirtyBuildingState().getLastBuildValue();
      } catch (InterruptedException e) {
        throw new IllegalStateException("Interruption unexpected: " + this, e);
      }
      return (lastBuildValue == null) ? null : ValueWithMetadata.justValue(lastBuildValue);
    } else {
      // Value has not finished evaluating. It's probably about to be cleaned from the graph.
      return null;
    }
  }

  @Override
  public synchronized Iterable<SkyKey> getDirectDeps() {
    return getGroupedDirectDeps().toSet();
  }

  /**
   * If {@code isDone()}, returns the ordered list of sets of grouped direct dependencies that were
   * added in {@link #addTemporaryDirectDeps}.
   */
  public synchronized GroupedList<SkyKey> getGroupedDirectDeps() {
    assertKeepEdges();
    Preconditions.checkState(isDone(), "no deps until done. NodeEntry: %s", this);
    return GroupedList.create(directDeps);
  }

  @Override
  @Nullable
  public synchronized ErrorInfo getErrorInfo() {
    Preconditions.checkState(isDone(), "no errors until done. NodeEntry: %s", this);
    return ValueWithMetadata.getMaybeErrorInfo(value);
  }

  private DirtyBuildingState getDirtyBuildingState() {
    return (DirtyBuildingState) Preconditions.checkNotNull(buildingState, this);
  }

  /**
   * Puts entry in "done" state, as checked by {@link #isDone}. Subclasses that override one may
   * need to override the other.
   */
  protected void markDone() {
    buildingState = null;
  }

  protected synchronized Set<SkyKey> setStateFinishedAndReturnReverseDepsToSignal() {
    // Get reverse deps that need to be signaled.
    ImmutableSet<SkyKey> reverseDepsToSignal = buildingState.getReverseDepsToSignal();
    getReverseDepsUtil().addReverseDeps(this, reverseDepsToSignal);
    // Force consistency check and consolidate rdeps changes.
    getReverseDepsUtil().consolidateReverseDeps(this);
    this.directDeps = getTemporaryDirectDeps().compress();

    markDone();

    if (!keepEdges()) {
      this.directDeps = null;
      this.reverseDeps = null;
    }
    return reverseDepsToSignal;
  }

  @Override
  public synchronized Set<SkyKey> getInProgressReverseDeps() {
    Preconditions.checkState(!isDone(), this);
    return buildingState.getReverseDepsToSignal();
  }

  @Override
  public synchronized Set<SkyKey> setValue(SkyValue value, Version version)
      throws InterruptedException {
    Preconditions.checkState(isReady(), "%s %s", this, value);
    // This check may need to be removed when we move to a non-linear versioning sequence.
    Preconditions.checkState(
        this.lastChangedVersion.atMost(version), "%s %s %s", this, version, value);
    Preconditions.checkState(
        this.lastEvaluatedVersion.atMost(version), "%s %s %s", this, version, value);
    this.lastEvaluatedVersion = version;

    if (isDirty() && getDirtyBuildingState().unchangedFromLastBuild(value)) {
      // If the value is the same as before, just use the old value. Note that we don't use the new
      // value, because preserving == equality is even better than .equals() equality.
      this.value = getDirtyBuildingState().getLastBuildValue();
    } else {
      // If this is a new value, or it has changed since the last build, set the version to the
      // current graph version.
      this.lastChangedVersion = version;
      this.value = value;
    }

    return setStateFinishedAndReturnReverseDepsToSignal();
  }

  protected ReverseDepsUtil<InMemoryNodeEntry> getReverseDepsUtil() {
    return REVERSE_DEPS_UTIL;
  }

  @Override
  public synchronized DependencyState addReverseDepAndCheckIfDone(SkyKey reverseDep) {
    if (reverseDep != null) {
      if (keepEdges()) {
        getReverseDepsUtil().maybeCheckReverseDepNotPresent(this, reverseDep);
      }
      if (isDone()) {
        if (keepEdges()) {
          getReverseDepsUtil().addReverseDeps(this, ImmutableList.of(reverseDep));
        }
      } else {
        // Parent should never register itself twice in the same build.
        buildingState.addReverseDepToSignal(reverseDep);
      }
    }
    if (isDone()) {
      return DependencyState.DONE;
    }
    return buildingState.startEvaluating() ? DependencyState.NEEDS_SCHEDULING
                                           : DependencyState.ALREADY_EVALUATING;
  }

  @Override
  public synchronized DependencyState checkIfDoneForDirtyReverseDep(SkyKey reverseDep) {
    Preconditions.checkNotNull(reverseDep, this);
    Preconditions.checkState(keepEdges(), "%s %s", reverseDep, this);
    if (!isDone()) {
      getReverseDepsUtil().removeReverseDep(this, reverseDep);
      buildingState.addReverseDepToSignal(reverseDep);
    } else {
      getReverseDepsUtil().checkReverseDep(this, reverseDep);
    }
    return addReverseDepAndCheckIfDone(null);
  }

  @Override
  public synchronized void removeReverseDep(SkyKey reverseDep) {
    if (!keepEdges()) {
      return;
    }
    getReverseDepsUtil().removeReverseDep(this, reverseDep);
  }

  @Override
  public synchronized void removeInProgressReverseDep(SkyKey reverseDep) {
    buildingState.removeReverseDepToSignal(reverseDep);
  }

  @Override
  public synchronized Iterable<SkyKey> getReverseDeps() {
    assertKeepEdges();
    Iterable<SkyKey> reverseDeps = getReverseDepsUtil().getReverseDeps(this);
    if (isDone()) {
      return reverseDeps;
    } else {
      return Iterables.concat(reverseDeps, buildingState.getReverseDepsToSignal());
    }
  }

  @Override
  public synchronized boolean signalDep() {
    return signalDep(/*childVersion=*/ IntVersion.of(Long.MAX_VALUE));
  }

  @Override
  public synchronized boolean signalDep(Version childVersion) {
    Preconditions.checkState(!isDone(), "Value must not be done in signalDep %s", this);
    return buildingState.signalDep(
        /*childChanged=*/ !childVersion.atMost(lastEvaluatedVersion),
        getTemporaryDirectDeps().numElements());
  }

  @Override
  public synchronized boolean isDirty() {
    return !isDone() && buildingState.isDirty();
  }

  @Override
  public synchronized boolean isChanged() {
    return !isDone() && buildingState.isChanged();
  }

  /** Checks that a caller is not trying to access not-stored graph edges. */
  private void assertKeepEdges() {
    Preconditions.checkState(keepEdges(), "Graph edges not stored. %s", this);
  }

  @Override
  public synchronized MarkedDirtyResult markDirty(boolean isChanged) {
    assertKeepEdges();
    if (isDone()) {
      buildingState =
          DirtyBuildingState.create(isChanged, GroupedList.<SkyKey>create(directDeps), value);
      value = null;
      directDeps = null;
      return new MarkedDirtyResult(getReverseDepsUtil().getReverseDeps(this));
    }
    // The caller may be simultaneously trying to mark this node dirty and changed, and the dirty
    // thread may have lost the race, but it is the caller's responsibility not to try to mark
    // this node changed twice. The end result of racing markers must be a changed node, since one
    // of the markers is trying to mark the node changed.
    Preconditions.checkState(isChanged != isChanged(),
        "Cannot mark node dirty twice or changed twice: %s", this);
    Preconditions.checkState(value == null, "Value should have been reset already %s", this);
    if (isChanged) {
      // If the changed marker lost the race, we just need to mark changed in this method -- all
      // other work was done by the dirty marker.
      getDirtyBuildingState().markChanged();
    }
    return null;
  }

  @Override
  public synchronized Set<SkyKey> markClean() throws InterruptedException {
    this.value = getDirtyBuildingState().getLastBuildValue();
    Preconditions.checkState(isReady(), "Should be ready when clean: %s", this);
    Preconditions.checkState(
        getDirtyBuildingState().depsUnchangedFromLastBuild(getTemporaryDirectDeps()),
        "Direct deps must be the same as those found last build for node to be marked clean: %s",
        this);
    Preconditions.checkState(isDirty(), this);
    Preconditions.checkState(!buildingState.isChanged(), "shouldn't be changed: %s", this);
    return setStateFinishedAndReturnReverseDepsToSignal();
  }

  @Override
  public synchronized void forceRebuild() {
    Preconditions.checkState(
        getTemporaryDirectDeps().numElements() == getDirtyBuildingState().getSignaledDeps(), this);
    getDirtyBuildingState().forceChanged();
  }

  @Override
  public synchronized Version getVersion() {
    return lastChangedVersion;
  }

  /** @see DirtyBuildingState#getDirtyState() */
  @Override
  public synchronized NodeEntry.DirtyState getDirtyState() {
    return getDirtyBuildingState().getDirtyState();
  }

  /** @see DirtyBuildingState#getNextDirtyDirectDeps() */
  @Override
  public synchronized Collection<SkyKey> getNextDirtyDirectDeps() {
    Preconditions.checkState(isReady(), this);
    return getDirtyBuildingState().getNextDirtyDirectDeps();
  }

  @Override
  public synchronized Iterable<SkyKey> getAllDirectDepsForIncompleteNode() {
    Preconditions.checkState(!isDone(), this);
    if (!isDirty()) {
      return Iterables.concat(getTemporaryDirectDeps());
    } else {
      // There may be duplicates here. Make sure everything is unique.
      ImmutableSet.Builder<SkyKey> result = ImmutableSet.builder();
      for (Iterable<SkyKey> group : getTemporaryDirectDeps()) {
        result.addAll(group);
      }
      result.addAll(
          getDirtyBuildingState().getAllRemainingDirtyDirectDeps(/*preservePosition=*/ false));
      return result.build();
    }
  }

  @Override
  public synchronized Set<SkyKey> getAllRemainingDirtyDirectDeps() {
    if (isDirty()) {
      Preconditions.checkState(
          getDirtyBuildingState().getDirtyState() == DirtyState.REBUILDING, this);
      return getDirtyBuildingState().getAllRemainingDirtyDirectDeps(/*preservePosition=*/ true);
    } else {
      Preconditions.checkState(buildingState.isEvaluating(), this);
      return ImmutableSet.of();
    }
  }

  @Override
  public synchronized void markRebuilding() {
    getDirtyBuildingState().markRebuilding();
  }

  @SuppressWarnings("unchecked")
  @Override
  public synchronized GroupedList<SkyKey> getTemporaryDirectDeps() {
    Preconditions.checkState(!isDone(), "temporary shouldn't be done: %s", this);
    if (directDeps == null) {
      // Initialize lazily, to save a little bit of memory.
      directDeps = new GroupedList<SkyKey>();
    }
    return (GroupedList<SkyKey>) directDeps;
  }

  @Override
  public synchronized boolean noDepsLastBuild() {
    return getDirtyBuildingState().noDepsLastBuild();
  }

  /**
   * {@inheritDoc}
   *
   * <p>This is complicated by the need to maintain the group data. If we remove a dep that ended a
   * group, then its predecessor's group data must be changed to indicate that it now ends the
   * group.
   */
  @Override
  public synchronized void removeUnfinishedDeps(Set<SkyKey> unfinishedDeps) {
    getTemporaryDirectDeps().remove(unfinishedDeps);
  }

  @Override
  public synchronized Set<SkyKey> addTemporaryDirectDeps(GroupedListHelper<SkyKey> helper) {
    Preconditions.checkState(!isDone(), "add temp shouldn't be done: %s %s", helper, this);
    return getTemporaryDirectDeps().append(helper);
  }

  @Override
  public synchronized void addTemporaryDirectDepsGroupToDirtyEntry(Collection<SkyKey> group) {
    Preconditions.checkState(!isDone(), "add group temp shouldn't be done: %s %s", group, this);
    getTemporaryDirectDeps().appendGroup(group);
  }

  @Override
  public synchronized boolean isReady() {
    Preconditions.checkState(!isDone(), "can't be ready if done: %s", this);
    return buildingState.isReady(getTemporaryDirectDeps().numElements());
  }

  @Override
  public synchronized String toString() {
    return MoreObjects.toStringHelper(this)
        .add("identity", System.identityHashCode(this))
        .add("value", value)
        .add("lastChangedVersion", lastChangedVersion)
        .add("lastEvaluatedVersion", lastEvaluatedVersion)
        .add("directDeps", isDone() ? GroupedList.create(directDeps) : directDeps)
        .add("reverseDeps", getReverseDepsUtil().toString(this))
        .add("buildingState", buildingState)
        .toString();
  }

  /**
   * Do not use except in custom evaluator implementations! Added only temporarily.
   *
   * <p>Clones a InMemoryMutableNodeEntry iff it is a done node. Otherwise it fails.
   */
  public synchronized InMemoryNodeEntry cloneNodeEntry() {
    // As this is temporary, for now let's limit to done nodes.
    Preconditions.checkState(isDone(), "Only done nodes can be copied: %s", this);
    InMemoryNodeEntry nodeEntry = new InMemoryNodeEntry();
    nodeEntry.value = value;
    nodeEntry.lastChangedVersion = this.lastChangedVersion;
    nodeEntry.lastEvaluatedVersion = this.lastEvaluatedVersion;
    getReverseDepsUtil().addReverseDeps(nodeEntry, getReverseDepsUtil().getReverseDeps(this));
    nodeEntry.directDeps = directDeps;
    nodeEntry.buildingState = null;
    return nodeEntry;
  }
}
