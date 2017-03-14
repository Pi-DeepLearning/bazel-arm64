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

package com.google.devtools.build.lib.rules.java;

import com.google.devtools.build.lib.rules.SkylarkApiProvider;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;

/**
 * A class that exposes the java_toolchain providers to Skylark. It is intended to provide a
 * simple and stable interface for Skylark users.
 */
@SkylarkModule(
    name = "JavaToolchainSkylarkApiProvider",
    doc = "Provides access to information about the Java toolchain rule. "
    + "Accessible as a 'java_toolchain' field on a Target struct.")
public final class JavaToolchainSkylarkApiProvider extends SkylarkApiProvider {
  /** The name of the field in Skylark used to access this class. */
  public static final String NAME = "java_toolchain";

  /** @return the input Java language level */
  @SkylarkCallable(
      name = "source_version",
      doc = "The java source version.",
      structField = true
  )
  public String getSourceVersion() {
    JavaToolchainProvider javaToolchainProvider =
        getInfo().getProvider(JavaToolchainProvider.class);
    return javaToolchainProvider.getSourceVersion();
  }

  /** @return the target Java language level */
  @SkylarkCallable(
      name = "target_version",
      doc = "The java target version.",
      structField = true
  )
  public String getTargetVersion() {
    JavaToolchainProvider javaToolchainProvider =
        getInfo().getProvider(JavaToolchainProvider.class);
    return javaToolchainProvider.getTargetVersion();
  }

}
