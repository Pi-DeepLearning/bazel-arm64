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

package com.google.devtools.build.lib.rules.objc;

import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.rules.apple.AppleConfiguration;
import com.google.devtools.build.lib.rules.apple.Platform;

/**
 * Support for registering actions using the Apple tool "lipo", which combines artifacts of
 * different architectures to make multi-architecture artifacts.
 */
public class LipoSupport {
  private final RuleContext ruleContext;
  
  public LipoSupport(RuleContext ruleContext) {
    this.ruleContext = ruleContext;
  }

  /**
   * Registers an action to invoke "lipo" on all artifacts in {@code inputBinaries} to create the
   * {@code outputBinary} multi-architecture artifact, built for platform {@code platform}.
   *
   * @return this object
   */
  public LipoSupport registerCombineArchitecturesAction(NestedSet<Artifact> inputBinaries,
      Artifact outputBinary, Platform platform) {

    ruleContext.registerAction(ObjcRuleClasses.spawnAppleEnvActionBuilder(
            ruleContext.getFragment(AppleConfiguration.class), platform)
        .setMnemonic("ObjcCombiningArchitectures")
        .addTransitiveInputs(inputBinaries)
        .addOutput(outputBinary)
        .setExecutable(CompilationSupport.xcrunwrapper(ruleContext))
        .setCommandLine(CustomCommandLine.builder()
            .add(ObjcRuleClasses.LIPO)
            .addExecPaths("-create", inputBinaries)
            .addExecPath("-o", outputBinary)
            .build())
        .build(ruleContext));
    
    return this;
  }
}
