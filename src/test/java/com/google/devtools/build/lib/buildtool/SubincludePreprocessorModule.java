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
package com.google.devtools.build.lib.buildtool;

import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.packages.util.SubincludePreprocessor;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.WorkspaceBuilder;

public class SubincludePreprocessorModule extends BlazeModule {
  @Override
  public void workspaceInit(BlazeDirectories directories, WorkspaceBuilder builder) {
    builder.setPreprocessorFactorySupplier(new SubincludePreprocessor.FactorySupplier());
  }
}
