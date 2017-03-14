// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.java;

import static com.google.devtools.build.lib.packages.Attribute.ConfigurationTransition.HOST;
import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;
import static com.google.devtools.build.lib.packages.BuildType.LICENSE;
import static com.google.devtools.build.lib.syntax.Type.BOOLEAN;
import static com.google.devtools.build.lib.syntax.Type.STRING;
import static com.google.devtools.build.lib.syntax.Type.STRING_LIST;
import static com.google.devtools.build.lib.syntax.Type.STRING_LIST_DICT;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder;
import com.google.devtools.build.lib.util.FileTypeSet;
import java.util.List;

/**
 * Rule definition for {@code java_toolchain}
 */
public final class JavaToolchainRule implements RuleDefinition {
  @Override
  public RuleClass build(Builder builder, RuleDefinitionEnvironment env) {
    return builder
        .requiresConfigurationFragments(JavaConfiguration.class)
        /* <!-- #BLAZE_RULE(java_plugin).ATTRIBUTE(output_licenses) -->
        See <a href="${link common-definitions#binary.output_licenses}"><code>common attributes
        </code></a>
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("output_licenses", LICENSE))
        /* <!-- #BLAZE_RULE(java_toolchain).ATTRIBUTE(source_version) -->
        The Java source version (e.g., '6' or '7'). It specifies which set of code structures
        are allowed in the Java source code.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("source_version", STRING).mandatory()) // javac -source flag value.
        /* <!-- #BLAZE_RULE(java_toolchain).ATTRIBUTE(target_version) -->
        The Java target version (e.g., '6' or '7'). It specifies for which Java runtime the class
        should be build.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("target_version", STRING).mandatory()) // javac -target flag value.
        /* <!-- #BLAZE_RULE(java_toolchain).ATTRIBUTE(bootclasspath) -->
        The Java target exdir entries. Corresponds to javac's -bootclasspath flag.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(
            attr("bootclasspath", LABEL_LIST)
                .mandatory()
                .cfg(HOST)
                .allowedFileTypes(FileTypeSet.ANY_FILE))
        /* <!-- #BLAZE_RULE(java_toolchain).ATTRIBUTE(extclasspath) -->
        The Java target exdir entries. Corresponds to javac's -extdir flag.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(
            attr("extclasspath", LABEL_LIST)
                .mandatory()
                .cfg(HOST)
                .allowedFileTypes(FileTypeSet.ANY_FILE))
        /* <!-- #BLAZE_RULE(java_toolchain).ATTRIBUTE(encoding) -->
        The encoding of the java files (e.g., 'UTF-8').
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("encoding", STRING).mandatory()) // javac -encoding flag value.
        /* <!-- #BLAZE_RULE(java_toolchain).ATTRIBUTE(xlint) -->
        The list of warning to add or removes from default list. Precedes it with a dash to
        removes it. Please see the Javac documentation on the -Xlint options for more information.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("xlint", STRING_LIST).value(ImmutableList.<String>of()))
        /* <!-- #BLAZE_RULE(java_toolchain).ATTRIBUTE(misc) -->
        The list of extra arguments for the Java compiler. Please refer to the Java compiler
        documentation for the extensive list of possible Java compiler flags.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("misc", STRING_LIST).value(ImmutableList.<String>of()))
        /* <!-- #BLAZE_RULE(java_toolchain).ATTRIBUTE(jvm_opts) -->
        The list of arguments for the JVM when invoking the Java compiler. Please refer to the Java
        virtual machine documentation for the extensive list of possible flags for this option.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("jvm_opts", STRING_LIST).value(ImmutableList.<String>of()))
        /* <!-- #BLAZE_RULE(java_toolchain).ATTRIBUTE(javac_supports_workers) -->
        True if JavaBuilder supports running as a persistent worker, false if it doesn't.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("javac_supports_workers", BOOLEAN).value(true))
        /* <!-- #BLAZE_RULE(java_toolchain).ATTRIBUTE(javac) -->
        Label of the javac jar.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("javac", LABEL_LIST).mandatory().cfg(HOST).allowedFileTypes(FileTypeSet.ANY_FILE))
        /* <!-- #BLAZE_RULE(java_toolchain).ATTRIBUTE(javabuilder) -->
        Label of the JavaBuilder deploy jar.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(
            attr("javabuilder", LABEL_LIST)
                .mandatory()
                .cfg(HOST)
                .allowedFileTypes(FileTypeSet.ANY_FILE)
                .exec())
        /* <!-- #BLAZE_RULE(java_toolchain).ATTRIBUTE(singlejar) -->
        Label of the SingleJar deploy jar.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(
            attr("singlejar", LABEL_LIST)
                .mandatory()
                .cfg(HOST)
                .allowedFileTypes(FileTypeSet.ANY_FILE)
                .exec())
        /* <!-- #BLAZE_RULE(java_toolchain).ATTRIBUTE(genclass) -->
        Label of the GenClass deploy jar.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(
            attr("genclass", LABEL_LIST)
                .mandatory()
                .cfg(HOST)
                .allowedFileTypes(FileTypeSet.ANY_FILE)
                .exec())
        /* <!-- #BLAZE_RULE(java_toolchain).ATTRIBUTE(ijar) -->
        Label of the ijar executable.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(
            attr("ijar", LABEL_LIST)
                .mandatory()
                .cfg(HOST)
                .allowedFileTypes(FileTypeSet.ANY_FILE)
                .exec())
        /* <!-- #BLAZE_RULE(java_toolchain).ATTRIBUTE(header_compiler) -->
        Label of the header compiler. Required if --java_header_compilation is enabled.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(
            attr("header_compiler", LABEL_LIST)
                .cfg(HOST)
                .allowedFileTypes(FileTypeSet.ANY_FILE)
                .exec())
        /* <!-- #BLAZE_RULE(java_toolchain).ATTRIBUTE(forcibly_disable_header_compilation) -->
        Overrides --java_header_compilation to disable header compilation on platforms that do not
        support it, e.g. JDK 7 Bazel.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("forcibly_disable_header_compilation", BOOLEAN).value(false))
        .add(
            attr("compatible_javacopts", STRING_LIST_DICT)
                .undocumented("internal")
                .value(ImmutableMap.<String, List<String>>of()))
        .build();
  }

  @Override
  public Metadata getMetadata() {
    return RuleDefinition.Metadata.builder()
        .name("java_toolchain")
        .ancestors(BaseRuleClasses.BaseRule.class)
        .factoryClass(JavaToolchain.class)
        .build();
  }
}
/*<!-- #BLAZE_RULE (NAME = java_toolchain, TYPE = OTHER, FAMILY = Java) -->

<p>
Specifies the configuration for the Java compiler. Which toolchain to be used can be changed through
the --java_toolchain argument. Normally you should not write those kind of rules unless you want to
tune your Java compiler.
</p>

<h4 id="java_binary_examples">Examples</h4>

<p>A simple example would be:
</p>

<pre class="code">
java_toolchain(
    name = "toolchain",
    source_version = "7",
    target_version = "7",
    bootclasspath = ["//tools/jdk:bootclasspath"],
    encoding = "UTF-8",
    xlint = [ "classfile", "divzero", "empty", "options", "path" ],
    misc = [ "-g" ],
    javabuilder = ":JavaBuilder_deploy.jar",
)
</pre>

<!-- #END_BLAZE_RULE -->*/
