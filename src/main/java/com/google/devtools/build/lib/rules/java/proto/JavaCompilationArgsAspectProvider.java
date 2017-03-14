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

package com.google.devtools.build.lib.rules.java.proto;

import com.google.common.base.Function;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.rules.java.JavaCompilationArgsProvider;

import javax.annotation.Nullable;

/**
 * A wrapper around {@link JavaCompilationArgsProvider}.
 */
public class JavaCompilationArgsAspectProvider implements TransitiveInfoProvider {

  /**
   * A long way to say (wrapper) -> wrapper.provider.
   */
  public static final Function<
          ? super JavaCompilationArgsAspectProvider, ? extends JavaCompilationArgsProvider>
      GET_PROVIDER =
          new Function<JavaCompilationArgsAspectProvider, JavaCompilationArgsProvider>() {
            @Nullable
            @Override
            public JavaCompilationArgsProvider apply(
                @Nullable JavaCompilationArgsAspectProvider wrapper) {
              return wrapper == null ? null : wrapper.provider;
            }
          };

  public final JavaCompilationArgsProvider provider;

  public JavaCompilationArgsAspectProvider(JavaCompilationArgsProvider provider) {
    this.provider = provider;
  }
}
