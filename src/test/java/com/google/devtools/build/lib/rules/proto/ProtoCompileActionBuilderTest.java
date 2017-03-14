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

package com.google.devtools.build.lib.rules.proto;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER;
import static com.google.devtools.build.lib.rules.proto.ProtoCompileActionBuilder.createCommandLineFromToolchains;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Root;
import com.google.devtools.build.lib.actions.util.LabelArtifactOwner;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.rules.proto.ProtoCompileActionBuilder.ProtoCommandLineArgv;
import com.google.devtools.build.lib.rules.proto.ProtoCompileActionBuilder.ToolchainInvocation;
import com.google.devtools.build.lib.util.LazyString;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ProtoCompileActionBuilderTest {

  private static final InMemoryFileSystem FILE_SYSTEM = new InMemoryFileSystem();
  private final Root root = Root.asSourceRoot(FILE_SYSTEM.getPath("/"));
  private final Root derivedRoot =
      Root.asDerivedRoot(FILE_SYSTEM.getPath("/"), FILE_SYSTEM.getPath("/out"));

  @Test
  public void commandLine_basic() throws Exception {
    FilesToRunProvider plugin =
        new FilesToRunProvider(
            ImmutableList.<Artifact>of(),
            null /* runfilesSupport */,
            artifact("//:dont-care", "protoc-gen-javalite.exe"));

    ProtoLangToolchainProvider toolchainNoPlugin =
        ProtoLangToolchainProvider.create(
            "--java_out=param1,param2:$(OUT)",
            null /* pluginExecutable */,
            mock(TransitiveInfoCollection.class) /* runtime */,
            NestedSetBuilder.<Artifact>emptySet(STABLE_ORDER) /* blacklistedProtos */);

    ProtoLangToolchainProvider toolchainWithPlugin =
        ProtoLangToolchainProvider.create(
            "--$(PLUGIN_OUT)=param3,param4:$(OUT)",
            plugin,
            mock(TransitiveInfoCollection.class) /* runtime */,
            NestedSetBuilder.<Artifact>emptySet(STABLE_ORDER) /* blacklistedProtos */);

    SupportData supportData =
        SupportData.create(
            Predicates.<TransitiveInfoCollection>alwaysFalse(),
            ImmutableList.of(artifact("//:dont-care", "source_file.proto")),
            null /* protosInDirectDeps */,
            NestedSetBuilder.create(
                STABLE_ORDER,
                artifact("//:dont-care", "import1.proto"),
                artifact("//:dont-care", "import2.proto")),
            true /* hasProtoSources */);

    CustomCommandLine cmdLine =
        createCommandLineFromToolchains(
            ImmutableList.of(
                new ToolchainInvocation(
                    "dontcare_because_no_plugin", toolchainNoPlugin, "foo.srcjar"),
                new ToolchainInvocation("pluginName", toolchainWithPlugin, "bar.srcjar")),
            supportData.getDirectProtoSources(),
            supportData.getTransitiveImports(),
            supportData.getProtosInDirectDeps(),
            "//foo:bar",
            true /* allowServices */,
            ImmutableList.<String>of() /* protocOpts */);

    assertThat(cmdLine.arguments())
        .containsExactly(
            "--java_out=param1,param2:foo.srcjar",
            "--PLUGIN_pluginName_out=param3,param4:bar.srcjar",
            "--plugin=protoc-gen-PLUGIN_pluginName=protoc-gen-javalite.exe",
            "-Iimport1.proto=import1.proto",
            "-Iimport2.proto=import2.proto",
            "source_file.proto")
        .inOrder();
  }

  @Test
  public void commandLine_strictDeps() throws Exception {
    ProtoLangToolchainProvider toolchain =
        ProtoLangToolchainProvider.create(
            "--java_out=param1,param2:$(OUT)",
            null /* pluginExecutable */,
            mock(TransitiveInfoCollection.class) /* runtime */,
            NestedSetBuilder.<Artifact>emptySet(STABLE_ORDER) /* blacklistedProtos */);

    SupportData supportData =
        SupportData.create(
            Predicates.<TransitiveInfoCollection>alwaysFalse(),
            ImmutableList.of(artifact("//:dont-care", "source_file.proto")),
            NestedSetBuilder.create(STABLE_ORDER, artifact("//:dont-care", "import1.proto")),
            NestedSetBuilder.create(
                STABLE_ORDER,
                artifact("//:dont-care", "import1.proto"),
                artifact("//:dont-care", "import2.proto")),
            true /* hasProtoSources */);

    CustomCommandLine cmdLine =
        createCommandLineFromToolchains(
            ImmutableList.of(new ToolchainInvocation("dontcare", toolchain, "foo.srcjar")),
            supportData.getDirectProtoSources(),
            supportData.getTransitiveImports(),
            supportData.getProtosInDirectDeps(),
            "//foo:bar",
            true /* allowServices */,
            ImmutableList.<String>of() /* protocOpts */);

    assertThat(cmdLine.arguments())
        .containsExactly(
            "--java_out=param1,param2:foo.srcjar",
            "-Iimport1.proto=import1.proto",
            "-Iimport2.proto=import2.proto",
            "--direct_dependencies=import1.proto",
            "--direct_dependencies_violation_msg=%s is imported, "
                + "but //foo:bar doesn't directly depend on a proto_library that 'srcs' it.",
            "source_file.proto")
        .inOrder();
  }

  @Test
  public void otherParameters() throws Exception {
    SupportData supportData =
        SupportData.create(
            Predicates.<TransitiveInfoCollection>alwaysFalse(),
            ImmutableList.<Artifact>of(),
            null /* protosInDirectDeps */,
            NestedSetBuilder.<Artifact>emptySet(STABLE_ORDER),
            true /* hasProtoSources */);

    CustomCommandLine cmdLine =
        createCommandLineFromToolchains(
            ImmutableList.<ToolchainInvocation>of(),
            supportData.getDirectProtoSources(),
            supportData.getTransitiveImports(),
            supportData.getProtosInDirectDeps(),
            "//foo:bar",
            false /* allowServices */,
            ImmutableList.of("--foo", "--bar") /* protocOpts */);

    assertThat(cmdLine.arguments()).containsAllOf("--disallow_services", "--foo", "--bar");
  }

  @Test
  public void outReplacementAreLazilyEvaluated() throws Exception {
    final boolean[] hasBeenCalled = new boolean[1];
    hasBeenCalled[0] = false;

    CharSequence outReplacement =
        new LazyString() {
          @Override
          public String toString() {
            hasBeenCalled[0] = true;
            return "mu";
          }
        };

    ProtoLangToolchainProvider toolchain =
        ProtoLangToolchainProvider.create(
            "--java_out=param1,param2:$(OUT)",
            null /* pluginExecutable */,
            mock(TransitiveInfoCollection.class) /* runtime */,
            NestedSetBuilder.<Artifact>emptySet(STABLE_ORDER) /* blacklistedProtos */);

    SupportData supportData =
        SupportData.create(
            Predicates.<TransitiveInfoCollection>alwaysFalse(),
            ImmutableList.<Artifact>of(),
            null /* protosInDirectDeps */,
            NestedSetBuilder.<Artifact>emptySet(STABLE_ORDER),
            true /* hasProtoSources */);

    CustomCommandLine cmdLine =
        createCommandLineFromToolchains(
            ImmutableList.of(new ToolchainInvocation("pluginName", toolchain, outReplacement)),
            supportData.getDirectProtoSources(),
            supportData.getTransitiveImports(),
            supportData.getProtosInDirectDeps(),
            "//foo:bar",
            true /* allowServices */,
            ImmutableList.<String>of() /* protocOpts */);

    assertThat(hasBeenCalled[0]).isFalse();
    cmdLine.arguments();
    assertThat(hasBeenCalled[0]).isTrue();
  }

  /**
   * Tests that if the same invocation-name is specified by more than one invocation,
   * ProtoCompileActionBuilder throws an exception.
   */
  @Test
  public void exceptionIfSameName() throws Exception {
    SupportData supportData =
        SupportData.create(
            Predicates.<TransitiveInfoCollection>alwaysFalse(),
            ImmutableList.<Artifact>of(),
            null /* protosInDirectDeps */,
            NestedSetBuilder.<Artifact>emptySet(STABLE_ORDER),
            true /* hasProtoSources */);

    ProtoLangToolchainProvider toolchain1 =
        ProtoLangToolchainProvider.create(
            "dontcare",
            null /* pluginExecutable */,
            mock(TransitiveInfoCollection.class) /* runtime */,
            NestedSetBuilder.<Artifact>emptySet(STABLE_ORDER) /* blacklistedProtos */);

    ProtoLangToolchainProvider toolchain2 =
        ProtoLangToolchainProvider.create(
            "dontcare",
            null /* pluginExecutable */,
            mock(TransitiveInfoCollection.class) /* runtime */,
            NestedSetBuilder.<Artifact>emptySet(STABLE_ORDER) /* blacklistedProtos */);

    try {
      createCommandLineFromToolchains(
          ImmutableList.of(
              new ToolchainInvocation("pluginName", toolchain1, "outReplacement"),
              new ToolchainInvocation("pluginName", toolchain2, "outReplacement")),
          supportData.getDirectProtoSources(),
          supportData.getTransitiveImports(),
          supportData.getProtosInDirectDeps(),
          "//foo:bar",
          true /* allowServices */,
          ImmutableList.<String>of() /* protocOpts */);
      fail("Expected an exception");
    } catch (IllegalStateException e) {
      assertThat(e.getMessage())
          .isEqualTo(
              "Invocation name pluginName appears more than once. "
                  + "This could lead to incorrect proto-compiler behavior");
    }
  }

  @Test
  public void testProtoCommandLineArgv() throws Exception {
    assertThat(
            new ProtoCommandLineArgv(
                    null /* directDependencies */,
                    ImmutableList.of(derivedArtifact("//:dont-care", "foo.proto")))
                .argv())
        .containsExactly("-Ifoo.proto=out/foo.proto");

    assertThat(
            new ProtoCommandLineArgv(
                    ImmutableList.<Artifact>of() /* directDependencies */,
                    ImmutableList.of(derivedArtifact("//:dont-care", "foo.proto")))
                .argv())
        .containsExactly("-Ifoo.proto=out/foo.proto", "--direct_dependencies=");

    assertThat(
            new ProtoCommandLineArgv(
                    ImmutableList.of(
                        derivedArtifact("//:dont-care", "foo.proto")) /* directDependencies */,
                    ImmutableList.of(derivedArtifact("//:dont-care", "foo.proto")))
                .argv())
        .containsExactly("-Ifoo.proto=out/foo.proto", "--direct_dependencies=foo.proto");

    assertThat(
            new ProtoCommandLineArgv(
                    ImmutableList.of(
                        derivedArtifact("//:dont-care", "foo.proto"),
                        derivedArtifact("//:dont-care", "bar.proto")) /* directDependencies */,
                    ImmutableList.of(derivedArtifact("//:dont-care", "foo.proto")))
                .argv())
        .containsExactly("-Ifoo.proto=out/foo.proto", "--direct_dependencies=foo.proto:bar.proto");
  }

  /**
   * Include-maps are the -Ivirtual=physical arguments passed to proto-compiler. When including a
   * file named 'foo/bar.proto' from an external repository 'bla', the include-map should be
   * -Ifoo/bar.proto=external/bla/foo/bar.proto. That is - 'virtual' should be the path relative to
   * the external repo root, and physical should be the physical file location.
   */
  @Test
  public void testIncludeMapsOfExternalFiles() throws Exception {
    assertThat(
            new ProtoCommandLineArgv(
                    null /* protosInDirectoDependencies */,
                    ImmutableList.of(artifact("@bla//foo:bar", "external/bla/foo/bar.proto")))
                .argv())
        .containsExactly("-Ifoo/bar.proto=external/bla/foo/bar.proto");
  }

  private Artifact artifact(String ownerLabel, String path) {
    return new Artifact(
        root.getPath().getRelative(path),
        root,
        root.getExecPath().getRelative(path),
        new LabelArtifactOwner(Label.parseAbsoluteUnchecked(ownerLabel)));
  }

  /** Creates a dummy artifact with the given path, that actually resides in /out/<path>. */
  private Artifact derivedArtifact(String ownerLabel, String path) {
    return new Artifact(
        derivedRoot.getPath().getRelative(path),
        derivedRoot,
        derivedRoot.getExecPath().getRelative(path),
        new LabelArtifactOwner(Label.parseAbsoluteUnchecked(ownerLabel)));
  }
}
