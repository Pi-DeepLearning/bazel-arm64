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
package com.google.devtools.build.lib.rules.android;

import com.google.auto.value.AutoValue;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;

/** A provider that supplies ResourceContainers from its transitive closure. */
@AutoValue
@Immutable
public abstract class AndroidResourcesProvider implements TransitiveInfoProvider {

  public static AndroidResourcesProvider create(
      Label label,
      NestedSet<ResourceContainer> transitiveAndroidResources,
      NestedSet<ResourceContainer> directAndroidResources) {
    return new AutoValue_AndroidResourcesProvider(
        label, transitiveAndroidResources, directAndroidResources);
  }

  /**
   * Returns the label that is associated with this piece of information.
   */
  public abstract Label getLabel();

  /** Returns the transitive ResourceContainers for the label. */
  public abstract NestedSet<ResourceContainer> getTransitiveAndroidResources();

  /** Returns the immediate ResourceContainers for the label. */
  public abstract NestedSet<ResourceContainer> getDirectAndroidResources();

  AndroidResourcesProvider() {}
}
