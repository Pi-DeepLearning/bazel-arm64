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
package com.google.devtools.build.lib.worker;

import com.google.devtools.common.options.Converters;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.Options;
import com.google.devtools.common.options.OptionsBase;
import java.util.List;
import java.util.Map.Entry;

/**
 * Options related to worker processes.
 */
public class WorkerOptions extends OptionsBase {
  public static final WorkerOptions DEFAULTS = Options.getDefaults(WorkerOptions.class);

  @Option(
    name = "experimental_persistent_javac",
    defaultValue = "null",
    category = "strategy",
    help = "Enable the experimental persistent Java compiler.",
    expansion = {
      "--strategy=Javac=worker",
      "--strategy=JavaIjar=local",
      "--strategy=JavaDeployJar=local",
      "--strategy=JavaSourceJar=local",
      "--strategy=Turbine=local"
    }
  )
  public Void experimentalPersistentJavac;

  @Option(
    name = "worker_max_instances",
    defaultValue = "4",
    category = "strategy",
    help =
        "How many instances of a worker process (like the persistent Java compiler) may be "
            + "launched if you use the 'worker' strategy."
  )
  public int workerMaxInstances;

  @Option(
    name = "worker_max_retries",
    defaultValue = "3",
    category = "strategy",
    help = "If a worker fails during work, retry <worker_max_retries> times before giving up."
  )
  public int workerMaxRetries;

  @Option(
    name = "worker_quit_after_build",
    defaultValue = "false",
    category = "strategy",
    help = "If enabled, all workers quit after a build is done."
  )
  public boolean workerQuitAfterBuild;

  @Option(
    name = "worker_verbose",
    defaultValue = "false",
    category = "strategy",
    help = "If enabled, prints verbose messages when workers are started, shutdown, ..."
  )
  public boolean workerVerbose;

  @Option(
      name = "worker_extra_flag",
      converter = Converters.AssignmentConverter.class,
      defaultValue = "",
      category = "strategy",
      help =
          "Extra command-flags that will be passed to worker processes in addition to "
              + "--persistent_worker, keyed by mnemonic (e.g. --worker_extra_flag=Javac=--debug.",
      allowMultiple = true
  )
  public List<Entry<String, String>> workerExtraFlags;

  @Option(
    name = "worker_sandboxing",
    defaultValue = "false",
    category = "strategy",
    help = "If enabled, workers will be executed in a sandboxed environment."
  )
  public boolean workerSandboxing;
}
