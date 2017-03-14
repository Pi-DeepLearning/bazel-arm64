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
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.rules.cpp.LinkerInput;

/**
 * A target that can provide native libraries (they are always dynamic ones) to Android binaries.
 */
@AutoValue
@Immutable
public abstract class AndroidNativeLibraryProvider implements TransitiveInfoProvider {

  public static AndroidNativeLibraryProvider create(
      NestedSet<LinkerInput> transitiveAndroidNativeLibraries) {
    return new AutoValue_AndroidNativeLibraryProvider(transitiveAndroidNativeLibraries);
  }

  public abstract NestedSet<LinkerInput> getTransitiveAndroidNativeLibraries();

  AndroidNativeLibraryProvider() {}
}
