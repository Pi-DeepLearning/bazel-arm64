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
package com.google.devtools.build.lib.bazel.rules.genrule;

import static com.google.devtools.build.lib.packages.Attribute.ConfigurationTransition.HOST;
import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;
import static com.google.devtools.build.lib.packages.BuildType.LICENSE;
import static com.google.devtools.build.lib.packages.BuildType.OUTPUT_LIST;
import static com.google.devtools.build.lib.syntax.Type.BOOLEAN;
import static com.google.devtools.build.lib.syntax.Type.STRING;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder;
import com.google.devtools.build.lib.rules.ToolchainProvider;
import com.google.devtools.build.lib.util.FileTypeSet;

/**
 * Rule definition for the genrule rule.
 */
public final class BazelGenRuleRule implements RuleDefinition {
  public static final String GENRULE_SETUP_LABEL = "//tools/genrule:genrule-setup.sh";

  @Override
  public RuleClass build(Builder builder, RuleDefinitionEnvironment env) {
    /* <!-- #BLAZE_RULE(genrule).NAME -->
    <br/>You may refer to this rule by name in the
    <code>srcs</code> or <code>deps</code> section of other <code>BUILD</code>
    rules. If the rule generates source files, you should use the
    <code>srcs</code> attribute.
    <!-- #END_BLAZE_RULE.NAME --> */
    return builder
        .setOutputToGenfiles()
        /* <!-- #BLAZE_RULE(genrule).ATTRIBUTE(srcs) -->
        A list of inputs for this rule, such as source files to process.
        <p>
          <em>This attributes is not suitable to list tools executed by the <code>cmd</code>; use
          the <a href="${link genrule.tools}"><code>tools</code></a> attribute for them instead.
          </em>
        </p>
        <p>
          The build system ensures these prerequisites are built before running the genrule
          command; they are built using the same configuration as the original build request. The
          names of the files of these prerequisites are available to the command as a
          space-separated list in <code>$(SRCS)</code>; alternatively the path of an individual
          <code>srcs</code> target <code>//x:y</code> can be obtained using <code>$(location
          //x:y)</code>, or using <code>$&lt;</code> provided it's the only entry in
          //<code>srcs</code>.
        </p>
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("srcs", LABEL_LIST)
            .direct_compile_time_input()
            .legacyAllowAnyFileType())

        /* <!-- #BLAZE_RULE(genrule).ATTRIBUTE(tools) -->
        A list of <i>tool</i> dependencies for this rule. See the definition of
        <a href="../build-ref.html#deps">dependencies</a> for more information. <br/>
        <p>
          The build system ensures these prerequisites are built before running the genrule command;
          they are built using the <a href='../blaze-user-manual.html#configurations'><i>host</i>
          configuration</a>, since these tools are executed as part of the build. The path of an
          individual <code>tools</code> target <code>//x:y</code> can be obtained using
          <code>$(location //x:y)</code>.
        </p>
        <p>
          Any <code>*_binary</code> or tool to be executed by <code>cmd</code> must appear in this
          list, not in <code>srcs</code>, to ensure they are built in the correct configuration.
        </p>
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("tools", LABEL_LIST).cfg(HOST).legacyAllowAnyFileType())
        .add(attr("toolchains", LABEL_LIST)
            .allowedFileTypes(FileTypeSet.NO_FILE)
            .mandatoryNativeProviders(ImmutableList.<Class<? extends TransitiveInfoProvider>>of(
                ToolchainProvider.class)))
        .add(attr("$genrule_setup", LABEL).cfg(HOST).value(env.getToolsLabel(GENRULE_SETUP_LABEL)))

        /* <!-- #BLAZE_RULE(genrule).ATTRIBUTE(outs) -->
        A list of files generated by this rule.
        <p>
          Output files must not cross package boundaries.
          Output filenames are interpreted as relative to the package.
        </p>
        <p>
          If the <code>executable</code> flag is set, <code>outs</code> must contain exactly one
          label.
        </p>
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("outs", OUTPUT_LIST).mandatory())

        /* <!-- #BLAZE_RULE(genrule).ATTRIBUTE(cmd) -->
        The command to run.
        Subject to <a href="${link make-variables#location}">$(location)</a> and
        <a href="${link make-variables}">"Make" variable</a> substitution.
        <ol>
          <li>
            First <a href="${link make-variables#location}">$(location)</a> substitution is
            applied, replacing all occurrences of <code>$(location <i>label</i>)</code> and of
            <code>$(locations <i>label</i>)</code>.
          </li>
          <li>
            <p>
              Note that <code>outs</code> are <i>not</i> included in this substitution. Output files
              are always generated into a predictable location (available via <code>$(@D)</code>,
              <code>$@</code>, <code>$(OUTS)</code> or <code>$(location <i>output_name</i>)</code>;
              see below).
            </p>
          </li>
          <li>
            Next, <a href="${link make-variables}">"Make" variables</a> are expanded. Note that
            predefined variables <code>$(JAVA)</code>, <code>$(JAVAC)</code> and
            <code>$(JAVABASE)</code> expand under the <i>host</i> configuration, so Java invocations
            that run as part of a build step can correctly load shared libraries and other
            dependencies.
          </li>
          <li>
            Finally, the resulting command is executed using the Bash shell. If its exit code is
            non-zero the command is considered to have failed.
          </li>
        </ol>
        <p>
          The command may refer to <code>*_binary</code>targets; it should use a <a
          href="../build-ref.html#labels">label</a> for this. The following
          variables are available within the <code>cmd</code> sublanguage:</p>
        <ul>
          <li>
            <a href="${link make-variables#predefined_variables.genrule.cmd}">"Make" variables</a>
          </li>
          <li>
            "Make" variables that are predefined by the build tools.
            Please use these variables instead of hardcoded values.
            See <a href="${link make-variables#predefined_variables}">Predefined "Make" Variables
            </a> in this document for a list of supported values.
          </li>
        </ul>
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("cmd", STRING).mandatory())

        /* <!-- #BLAZE_RULE(genrule).ATTRIBUTE(output_to_bindir) -->
        <p>
          If set to 1, this option causes output files to be written into the <code>bin</code>
          directory instead of the <code>genfiles</code> directory.
        </p>
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        // TODO(bazel-team): find a location to document genfiles/binfiles, link to them from here.
        .add(attr("output_to_bindir", BOOLEAN).value(false)
            .nonconfigurable("policy decision: no reason for this to depend on the configuration"))

        /* <!-- #BLAZE_RULE(genrule).ATTRIBUTE(local) -->
        <p>
          If set to 1, this option force this <code>genrule</code> to run with the
          <code>standalone</code> strategy, without sandboxing.
        </p>
        <p>
          This is equivalent to providing 'local' as a tag (<code>tags=["local"]</code>). The
          local strategy is applied if either one is specified.
        </p>
        <p>
          The <code>--genrule_strategy</code> option value <code>local</code>
          overrides this attribute.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("local", BOOLEAN).value(false))

        /* <!-- #BLAZE_RULE(genrule).ATTRIBUTE(message) -->
        A progress message.
        <p>
          A progress message that will be printed as this build step is executed. By default, the
          message is "Generating <i>output</i>" (or something equally bland) but you may provide a
          more specific one. Use this attribute instead of <code>echo</code> or other print
          statements in your <code>cmd</code> command, as this allows the build tool to control
          whether such progress messages are printed or not.
        </p>
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("message", STRING))
        /*<!-- #BLAZE_RULE(genrule).ATTRIBUTE(output_licenses) -->
        See <a href="${link common-definitions#binary.output_licenses}"><code>common attributes
        </code></a>
        <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
        .add(attr("output_licenses", LICENSE))

        /* <!-- #BLAZE_RULE(genrule).ATTRIBUTE(executable) -->
        Declare output to be executable.
        <p>
          Setting this flag to 1 means the output is an executable file and can be run using the
          <code>run</code> command. The genrule must produce exactly one output in this case.
          If this attribute is set, <code>run</code> will try executing the file regardless of
          its content.
        </p>
        <p>Declaring data dependencies for the generated executable is not supported.</p>
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("executable", BOOLEAN).value(false).nonconfigurable(
            "Used in computed default for $is_executable, which is itself non-configurable (and "
            + " thus expects its dependencies to be non-configurable), because $is_executable"
            + " is called from RunCommand.isExecutable, which has no configuration context"))

        // TODO(bazel-team): stamping doesn't seem to work. Fix it or remove attribute.
        .add(attr("stamp", BOOLEAN).value(false))
        // This is a misfeature, so don't document it. We would like to get rid of it, but that
        // would require a cleanup of existing rules.
        .add(attr("heuristic_label_expansion", BOOLEAN).value(false))
        .add(attr("$is_executable", BOOLEAN)
            .nonconfigurable("Called from RunCommand.isExecutable, which takes a Target")
            .value(
            new Attribute.ComputedDefault() {
              @Override
              public Object getDefault(AttributeMap rule) {
                return (rule.get("outs", BuildType.OUTPUT_LIST).size() == 1)
                    && rule.get("executable", BOOLEAN);
              }
            }))
        .removeAttribute("data")
        .removeAttribute("deps")
        .build();
  }

  @Override
  public Metadata getMetadata() {
    return RuleDefinition.Metadata.builder()
        .name("genrule")
        .ancestors(BaseRuleClasses.RuleBase.class)
        .factoryClass(BazelGenRule.class)
        .build();
  }
}

/*<!-- #BLAZE_RULE (NAME = genrule, TYPE = OTHER, FAMILY = General)[GENERIC_RULE] -->

<p>A <code>genrule</code> generates one or more files using a user-defined Bash command.</p>

<p>
  Genrules are generic build rules that you can use if there's no specific rule for the task. If for
  example you want to minify JavaScript files then you can use a genrule to do so. If however you
  need to compile C++ files, stick to the existing <code>cc_*</code> rules, because all the heavy
  lifting has already been done for you.
</p>
<p>
  Do not use a genrule for running tests. There are special dispensations for tests and test
  results, including caching policies and environment variables. Tests generally need to be run
  after the build is complete and on the target architecture, whereas genrules are executed during
  the build and on the host architecture (the two may be different). If you need a general purpose
  testing rule, use <a href="${link sh_test}"><code>sh_test</code></a>.
</p>

<h4>Cross-compilation Considerations</h4>

<p>
  <em>See <a href="../bazel-user-manual.html#configurations">the user manual</a> for more info about
  cross-compilation.</em>
</p>
<p>
  While genrules run during a build, their outputs are often used after the build, for deployment or
  testing. Consider the example of compiling C code for a microcontroller: the compiler accepts C
  source files and generates code that runs on a microcontroller. The generated code obviously
  cannot run on the CPU that was used for building it, but the C compiler (if compiled from source)
  itself has to.
</p>
<p>
  The build system uses the host configuration to describe the machine(s) on which the build runs
  and the target configuration to describe the machine(s) on which the output of the build is
  supposed to run. It provides options to configure each of these and it segregates the
  corresponding files into separate directories to avoid conflicts.
</p>
<p>
  For genrules, the build system ensures that dependencies are built appropriately:
  <code>srcs</code> are built (if necessary) for the <em>target</em> configuration,
  <code>tools</code> are built for the <em>host</em> configuration, and the output is considered to
  be for the <em>target</em> configuration. It also provides <a href="${link make-variables}">
  "Make" variables</a> that genrule commands can pass to the corresponding tools.
</p>
<p>
  It is intentional that genrule defines no <code>deps</code> attribute: other built-in rules use
  language-dependent meta information passed between the rules to automatically determine how to
  handle dependent rules, but this level of automation is not possible for genrules. Genrules work
  purely at the file and runfiles level.
</p>

<h4>Special Cases</h4>

<p>
  <i>Host-host compilation</i>: in some cases, the build system needs to run genrules such that the
  output can also be executed during the build. If for example a genrule builds some custom compiler
  which is subsequently used by another genrule, the first one has to produce its output for the
  host configuration, because that's where the compiler will run in the other genrule. In this case,
  the build system does the right thing automatically: it builds the <code>srcs</code> and
  <code>outs</code> of the first genrule for the host configuration instead of the target
  configuration. See <a href="../bazel-user-manual.html#configurations">the user manual</a> for more
  info.
</p>
<p>
  <i>JDK & C++ Tooling</i>: to use a tool from the JDK or the C++ compiler suite, the build system
  provides a set of variables to use. See <a href="${link make-variables}">"Make" variable</a> for
  details.
</p>

<h4>Genrule Environment</h4>

<p>
  The genrule command is executed in a Bash shell, configured to fail when a command or a pipeline
  fails (<code>set -e -o pipefail</code>). Genrules should not access the network (except to create
  connections between processes running within the same genrule on the same machine), though this is
  not currently enforced.
</p>
<p>
  The build system automatically deletes any existing output files, but creates any necessary parent
  directories before it runs a genrule. It also removes any output files in case of a failure.
</p>

<h4>General Advice</h4>

<ul>
  <li>Do ensure that tools run by a genrule are deterministic and hermetic. They should not write
    timestamps to their output, and they should use stable ordering for sets and maps, as well as
    write only relative file paths to the output, no absolute paths. Not following this rule will
    lead to unexpected build behavior (Bazel not rebuilding a genrule you thought it would) and
    degrade cache performance.</li>
  <li>Do use <code>$(location)</code> extensively, for outputs, tools and sources. Due to the
    segregation of output files for different configurations, genrules cannot rely on hard-coded
    and/or absolute paths.</li>
  <li>Do write a common Skylark macro in case the same or very similar genrules are used in multiple
    places. If the genrule is complex, consider implementing it in a script or as a Skylark rule.
    This improves readability as well as testability.</li>
  <li>Do make sure that the exit code correctly indicates success or failure of the genrule.</li>
  <li>Do not write informational messages to stdout or stderr. While useful for debugging, this can
    easily become noise; a successful genrule should be silent. On the other hand, a failing genrule
    should emit good error messages.</li>
  <li><code>$$</code> evaluates to a <code>$</code>, a literal dollar-sign, so in order to invoke a
    shell command containing dollar-signs such as <code>ls $(dirname $x)</code>, one must escape it
    thus: <code>ls $$(dirname $$x)</code>.</li>
  <li>Avoid creating symlinks and directories. Bazel doesn't copy over the directory/symlink
    structure created by genrules and its dependency checking of directories is unsound.</li>
  <li>When referencing the genrule in other rules, you can use either the genrule's label or the
    labels of individual output files. Sometimes the one approach is more readable, sometimes the
    other: referencing outputs by name in a consuming rule's <code>srcs</code> will avoid
    unintentionally picking up other outputs of the genrule, but can be tedious if the genrule
    produces many outputs.</li>
</ul>

<h4 id="genrule_examples">Examples</h4>

<p>
  This example generates <code>foo.h</code>. There are no sources, because the command doesn't take
  any input. The "binary" run by the command is a perl script in the same package as the genrule.
</p>
<pre class="code">
genrule(
    name = "foo",
    srcs = [],
    outs = ["foo.h"],
    cmd = "./$(location create_foo.pl) &gt; \"$@\"",
    tools = ["create_foo.pl"],
)
</pre>

<p>
  The following example shows how to use a <a href="${link filegroup}"><code>filegroup</code>
  </a> and the outputs of another <code>genrule</code>. Note that using <code>$(SRCS)</code> instead
  of explicit <code>$(location)</code> directives would also work; this example uses the latter for
  sake of demonstration.
</p>
<pre class="code">
genrule(
    name = "concat_all_files",
    srcs = [
        "//some:files",  # a filegroup with multiple files in it ==> $(location<b>s</b>)
        "//other:gen",   # a genrule with a single output ==> $(location)
    ],
    outs = ["concatenated.txt"],
    cmd = "cat $(locations //some:files) $(location //other:gen) > $@",
)
</pre>

<!-- #END_BLAZE_RULE -->*/
