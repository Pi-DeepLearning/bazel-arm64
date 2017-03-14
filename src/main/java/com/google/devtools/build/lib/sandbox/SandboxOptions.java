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

package com.google.devtools.build.lib.sandbox;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.List;

/** Options for sandboxed execution. */
public class SandboxOptions extends OptionsBase {

  /**
   * A converter for customized path mounting pair from the parameter list of a bazel command
   * invocation. Pairs are expected to have the form 'source:target'.
   */
  public static final class MountPairConverter
      implements Converter<ImmutableMap.Entry<String, String>> {

    @Override
    public ImmutableMap.Entry<String, String> convert(String input) throws OptionsParsingException {

      List<String> paths = Lists.newArrayList();
      for (String path : input.split("(?<!\\\\):")) { // Split on ':' but not on '\:'
        if (path != null && !path.trim().isEmpty()) {
          paths.add(path.replace("\\:", ":"));
        } else {
          throw new OptionsParsingException(
              "Input "
                  + input
                  + " contains one or more empty paths. "
                  + "Input must be a single path to mount inside the sandbox or "
                  + "a mounting pair in the form of 'source:target'");
        }
      }

      if (paths.size() < 1 || paths.size() > 2) {
        throw new OptionsParsingException(
            "Input must be a single path to mount inside the sandbox or "
                + "a mounting pair in the form of 'source:target'");
      }

      return paths.size() == 1
          ? Maps.immutableEntry(paths.get(0), paths.get(0))
          : Maps.immutableEntry(paths.get(0), paths.get(1));
    }

    @Override
    public String getTypeDescription() {
      return "a single path or a 'source:target' pair";
    }
  }

  @Option(
    name = "ignore_unsupported_sandboxing",
    defaultValue = "false",
    category = "strategy",
    help = "Do not print a warning when sandboxed execution is not supported on this system."
  )
  public boolean ignoreUnsupportedSandboxing;

  @Option(
    name = "sandbox_debug",
    defaultValue = "false",
    category = "strategy",
    help =
        "Let the sandbox print debug information on execution. This might help developers of "
            + "Bazel or Skylark rules with debugging failures due to missing input files, etc."
  )
  public boolean sandboxDebug;

  @Option(
    name = "sandbox_block_path",
    allowMultiple = true,
    defaultValue = "",
    category = "config",
    help = "For sandboxed actions, disallow access to this path."
  )
  public List<String> sandboxBlockPath;

  @Option(
      name = "sandbox_tmpfs_path",
      allowMultiple = true,
      defaultValue = "",
      category = "config",
      help = "For sandboxed actions, mount an empty, writable directory at this path"
          + " (if supported by the sandboxing implementation, ignored otherwise)."
  )
  public List<String> sandboxTmpfsPath;

  @Option(
    name = "sandbox_add_mount_pair",
    allowMultiple = true,
    converter = MountPairConverter.class,
    defaultValue = "",
    category = "config",
    help = "Add additional path pair to mount in sandbox."
  )
  public List<ImmutableMap.Entry<String, String>> sandboxAdditionalMounts;
}
