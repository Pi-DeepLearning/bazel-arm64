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

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.events.Event;

import java.io.Serializable;
import java.util.Objects;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * A wrapper of {@link Event} that contains a tag of the label where the event was generated. This
 * class allows us to tell where the events are coming from when we group all the tags in a
 * NestedSet.
 *
 * <p>The only usage of this code for now is to be able to use --output_filter in Skyframe
 *
 * <p>This is intended only for use in alternative {@code MemoizingEvaluator} implementations.
 */
@Immutable
public final class TaggedEvents implements Serializable {

  @Nullable
  private final String tag;
  private final ImmutableCollection<Event> events;

  TaggedEvents(@Nullable String tag, ImmutableCollection<Event> events) {

    this.tag = tag;
    this.events = events;
  }

  @Nullable
  String getTag() {
    return tag;
  }

  ImmutableCollection<Event> getEvents() {
    return events;
  }

  /**
   * Returns <i>some</i> moderately sane representation of the events. Should never be used in
   * user-visible places, only for debugging and testing.
   */
  @Override
  public String toString() {
    return tag == null ? "<unknown>" : tag + ": " + Iterables.toString(events);
  }

  @Override
  public int hashCode() {
    return Objects.hash(tag, events);
  }

  @Override
  public boolean equals(Object other) {
    if (other == null || !other.getClass().equals(getClass())) {
      return false;
    }
    TaggedEvents that = (TaggedEvents) other;
    return Objects.equals(this.tag, that.tag) && Objects.equals(this.events, that.events);
  }
}
