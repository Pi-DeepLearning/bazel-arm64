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

package com.google.devtools.build.buildjar;

import com.google.devtools.build.buildjar.javac.BlazeJavacResult;
import com.google.devtools.build.buildjar.javac.FormattedDiagnostic;
import com.google.devtools.build.buildjar.javac.JavacRunner;
import java.io.IOException;

/**
 * A variant of SimpleJavaLibraryBuilder that attempts to reduce the compile-time classpath right
 * before invoking the compiler, based on extra information from provided .jdeps files. This mode is
 * enabled via the --reduce_classpath flag, only when Blaze runs with --experimental_java_classpath.
 *
 * <p>A fall-back mechanism detects whether javac fails because the classpath is incorrectly
 * discarding required entries, and re-attempts to compile with the full classpath.
 */
public class ReducedClasspathJavaLibraryBuilder extends SimpleJavaLibraryBuilder {

  /**
   * Attempts to minimize the compile-time classpath before invoking javac, falling back to a
   * regular compile.
   *
   * @param build A JavaLibraryBuildRequest request object describing what to compile
   * @throws IOException clean-up up the output directory fails
   */
  @Override
  BlazeJavacResult compileSources(JavaLibraryBuildRequest build, JavacRunner javacRunner)
      throws IOException {
    // Minimize classpath, but only if we're actually compiling some sources (some invocations of
    // JavaBuilder are only building resource jars).
    String compressedClasspath = build.getClassPath();
    if (!build.getSourceFiles().isEmpty()) {
      compressedClasspath =
          build.getDependencyModule().computeStrictClasspath(build.getClassPath());
    }
    if (compressedClasspath.isEmpty()) {
      // If the empty classpath is specified and javac is invoked programatically,
      // javac falls back to using the host classpath. We don't want JavaBuilder
      // to leak onto the compilation classpath, so we add the (hopefully empty)
      // class output directory to prevent that from happening.
      compressedClasspath = build.getClassDir();
    }

    // Compile!
    BlazeJavacResult result =
        javacRunner.invokeJavac(build.toBlazeJavacArguments(compressedClasspath));

    // If javac errored out because of missing entries on the classpath, give it another try.
    // TODO(bazel-team): check performance impact of additional retries.
    if (shouldFallBack(result)) {
      // TODO(cushon): warn for transitive classpath fallback

      // Reset output directories
      prepareSourceCompilation(build);

      // Fall back to the regular compile, but add extra checks to catch transitive uses
      result = javacRunner.invokeJavac(build.toBlazeJavacArguments(build.getClassPath()));
    }
    return result;
  }

  private static boolean shouldFallBack(BlazeJavacResult result) {
    if (result.isOk()) {
      return false;
    }
    for (FormattedDiagnostic diagnostic : result.diagnostics()) {
      String code = diagnostic.getCode();
      if (code.contains("doesnt.exist")
          || code.contains("cant.resolve")
          || code.contains("cant.access")) {
        return true;
      }
      // handle -Xdoclint:reference errors, which don't have a diagnostic code
      // TODO(cushon): this is locale-dependent
      if (diagnostic.getFormatted().contains("error: reference not found")) {
        return true;
      }
      // Error Prone wraps completion failures
      if (code.equals("compiler.err.error.prone.crash")
          && diagnostic
              .getFormatted()
              .contains("com.sun.tools.javac.code.Symbol$CompletionFailure")) {
        return true;
      }
    }
    if (result.output().contains("com.sun.tools.javac.code.Symbol$CompletionFailure")) {
      return true;
    }
    return false;
  }
}
