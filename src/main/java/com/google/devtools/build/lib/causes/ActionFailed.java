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
package com.google.devtools.build.lib.causes;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.vfs.Path;

/**
 * Class describing a {@link Cause} that is associated with an action. It is uniquely determined by
 * the path to the primary output. For reference, a Label is attached as well.
 */
public class ActionFailed implements Cause {
  private final Path path;
  private final Label label;

  public ActionFailed(Path path, Label label) {
    this.path = path;
    this.label = label;
  }

  @Override
  public String toString() {
    return path.toString();
  }

  @Override
  public Label getLabel() {
    return label;
  }

  @Override
  public BuildEventStreamProtos.BuildEventId getIdProto() {
    return BuildEventStreamProtos.BuildEventId.newBuilder()
        .setActionCompleted(
            BuildEventStreamProtos.BuildEventId.ActionCompletedId.newBuilder()
                .setPrimaryOutput(path.toString())
                .build())
        .build();
  }
}
