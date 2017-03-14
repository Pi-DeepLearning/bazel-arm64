// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.buildeventstream;

import com.google.devtools.build.lib.causes.Cause;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.protobuf.TextFormat;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import javax.annotation.concurrent.Immutable;

/**
 * Class of identifiers for publically posted events.
 *
 * <p>Since event identifiers need to be created before the actual event, the event IDs are highly
 * structured so that equal identifiers can easily be generated. The main way of pregenerating event
 * identifiers that do not accidentally coincide is by providing a target or a target pattern;
 * therefore, those (if provided) are made specially visible.
 */
@Immutable
public final class BuildEventId implements Serializable {
  private final BuildEventStreamProtos.BuildEventId protoid;

  private BuildEventId(BuildEventStreamProtos.BuildEventId protoid) {
    this.protoid = protoid;
  }

  @Override
  public int hashCode() {
    return Objects.hash(protoid);
  }

  @Override
  public boolean equals(Object other) {
    if (other == null || !other.getClass().equals(getClass())) {
      return false;
    }
    BuildEventId that = (BuildEventId) other;
    return Objects.equals(this.protoid, that.protoid);
  }

  @Override
  public String toString() {
    return "BuildEventId {" + TextFormat.printToString(protoid) + "}";
  }

  public BuildEventStreamProtos.BuildEventId asStreamProto() {
    return protoid;
  }

  public static BuildEventId unknownBuildEventId(String details) {
    BuildEventStreamProtos.BuildEventId.UnknownBuildEventId id =
        BuildEventStreamProtos.BuildEventId.UnknownBuildEventId.newBuilder()
            .setDetails(details)
            .build();
    return new BuildEventId(
        BuildEventStreamProtos.BuildEventId.newBuilder().setUnknown(id).build());
  }

  public static BuildEventId progressId(int count) {
    BuildEventStreamProtos.BuildEventId.ProgressId id =
        BuildEventStreamProtos.BuildEventId.ProgressId.newBuilder().setOpaqueCount(count).build();
    return new BuildEventId(
        BuildEventStreamProtos.BuildEventId.newBuilder().setProgress(id).build());
  }

  public static BuildEventId buildStartedId() {
    BuildEventStreamProtos.BuildEventId.BuildStartedId startedId =
        BuildEventStreamProtos.BuildEventId.BuildStartedId.getDefaultInstance();
    return new BuildEventId(
        BuildEventStreamProtos.BuildEventId.newBuilder().setStarted(startedId).build());
  }

  public static BuildEventId targetPatternExpanded(List<String> targetPattern) {
    BuildEventStreamProtos.BuildEventId.PatternExpandedId patternId =
        BuildEventStreamProtos.BuildEventId.PatternExpandedId.newBuilder()
            .addAllPattern(targetPattern)
            .build();
    return new BuildEventId(
        BuildEventStreamProtos.BuildEventId.newBuilder().setPattern(patternId).build());
  }

  public static BuildEventId targetCompleted(Label target) {
    BuildEventStreamProtos.BuildEventId.TargetCompletedId targetId =
        BuildEventStreamProtos.BuildEventId.TargetCompletedId.newBuilder()
            .setLabel(target.toString())
            .build();
    return new BuildEventId(
        BuildEventStreamProtos.BuildEventId.newBuilder().setTargetCompleted(targetId).build());
  }

  public static BuildEventId fromCause(Cause cause) {
    return new BuildEventId(cause.getIdProto());
  }

  public static BuildEventId testSummary(Label target) {
    BuildEventStreamProtos.BuildEventId.TestSummaryId summaryId =
        BuildEventStreamProtos.BuildEventId.TestSummaryId.newBuilder()
            .setLabel(target.toString())
            .build();
    return new BuildEventId(
        BuildEventStreamProtos.BuildEventId.newBuilder().setTestSummary(summaryId).build());
  }
}
