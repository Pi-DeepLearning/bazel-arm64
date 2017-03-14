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
#ifndef BAZEL_SRC_MAIN_CPP_STARTUP_OPTIONS_H_
#define BAZEL_SRC_MAIN_CPP_STARTUP_OPTIONS_H_

#include <map>
#include <memory>
#include <string>
#include <vector>

#include "src/main/cpp/util/exit_code.h"

namespace blaze {

class WorkspaceLayout;

// This class holds the parsed startup options for Blaze.
// These options and their defaults must be kept in sync with those in
// src/main/java/com/google/devtools/build/lib/runtime/BlazeServerStartupOptions.java.
// The latter are purely decorative (they affect the help message,
// which displays the defaults).  The actual defaults are defined
// in the constructor.
//
// TODO(bazel-team): The encapsulation is not quite right -- there are some
// places in blaze.cc where some of these fields are explicitly modified. Their
// names also don't conform to the style guide.
class StartupOptions {
 public:
  explicit StartupOptions(const WorkspaceLayout* workspace_layout);
  virtual ~StartupOptions();

  // Parses a single argument, either from the command line or from the .blazerc
  // "startup" options.
  //
  // rcfile should be an empty string if the option being parsed does not come
  // from a blazerc.
  //
  // Sets "is_space_separated" true if arg is unary and uses the "--foo bar"
  // style, so its value is in next_arg.
  //
  // Sets "is_space_separated" false if arg is either nullary
  // (e.g. "--[no]batch") or is unary but uses the "--foo=bar" style.
  //
  // Returns the exit code after processing the argument. "error" will contain
  // a descriptive string for any return value other than
  // blaze_exit_code::SUCCESS.
  blaze_exit_code::ExitCode ProcessArg(const std::string &arg,
                                       const std::string &next_arg,
                                       const std::string &rcfile,
                                       bool *is_space_separated,
                                       std::string *error);

  // Adds any other options needed to result.
  //
  // TODO(jmmv): Now that we support site-specific options via subclasses of
  // StartupOptions, the "ExtraOptions" concept makes no sense; remove it.
  virtual void AddExtraOptions(std::vector<std::string> *result) const;

  // Checks extra fields when processing arg.
  //
  // Returns the exit code after processing the argument. "error" will contain
  // a descriptive string for any return value other than
  // blaze_exit_code::SUCCESS.
  //
  // TODO(jmmv): Now that we support site-specific options via subclasses of
  // StartupOptions, the "ExtraOptions" concept makes no sense; remove it.
  virtual blaze_exit_code::ExitCode ProcessArgExtra(
      const char *arg, const char *next_arg, const std::string &rcfile,
      const char **value, bool *is_processed, std::string *error);

  // Return the default path to the JDK used to run Blaze itself
  // (must be an absolute directory).
  virtual std::string GetDefaultHostJavabase() const;

  // Returns the path to the JVM. This should be called after parsing
  // the startup options.
  virtual std::string GetJvm();

  // Returns the executable used to start the Blaze server, typically the given
  // JVM.
  virtual std::string GetExe(const std::string &jvm,
                             const std::string &jar_path);

  // Adds JVM prefix flags to be set. These will be added before all other
  // JVM flags.
  virtual void AddJVMArgumentPrefix(const std::string &javabase,
                                    std::vector<std::string> *result) const;

  // Adds JVM suffix flags. These will be added after all other JVM flags, and
  // just before the Blaze server startup flags.
  virtual void AddJVMArgumentSuffix(const std::string &real_install_dir,
                                    const std::string &jar_path,
                                    std::vector<std::string> *result) const;

  // Adds JVM tuning flags for Blaze.
  //
  // Returns the exit code after this operation. "error" will be set to a
  // descriptive string for any value other than blaze_exit_code::SUCCESS.
  virtual blaze_exit_code::ExitCode AddJVMArguments(
      const std::string &host_javabase, std::vector<std::string> *result,
      const std::vector<std::string> &user_options, std::string *error) const;

  // Checks whether the argument is a valid nullary option.
  // E.g. --master_bazelrc, --nomaster_bazelrc.
  bool IsNullary(const std::string& arg) const;

  // Checks whether the argument is a valid unary option.
  // E.g. --blazerc=foo, --blazerc foo.
  bool IsUnary(const std::string& arg) const;

  std::string GetLowercaseProductName() const;

  // The capitalized name of this binary.
  const std::string product_name;

  // Blaze's output base.  Everything is relative to this.  See
  // the BlazeDirectories Java class for details.
  std::string output_base;

  // Installation base for a specific release installation.
  std::string install_base;

  // The toplevel directory containing Blaze's output.  When Blaze is
  // run by a test, we use TEST_TMPDIR, simplifying the correct
  // hermetic invocation of Blaze from tests.
  std::string output_root;

  // Blaze's output_user_root. Used only for computing install_base and
  // output_base.
  std::string output_user_root;

  // Whether to put the execroot at $OUTPUT_BASE/$WORKSPACE_NAME (if false) or
  // $OUTPUT_BASE/execroot/$WORKSPACE_NAME (if true).
  bool deep_execroot;

  // Block for the Blaze server lock. Otherwise,
  // quit with non-0 exit code if lock can't
  // be acquired immediately.
  bool block_for_lock;

  bool host_jvm_debug;

  std::string host_jvm_profile;

  std::vector<std::string> host_jvm_args;

  bool batch;

  // From the man page: "This policy is useful for workloads that are
  // non-interactive, but do not want to lower their nice value, and for
  // workloads that want a deterministic scheduling policy without
  // interactivity causing extra preemptions (between the workload's tasks)."
  bool batch_cpu_scheduling;

  // If negative, don't mess with ionice. Otherwise, set a level from 0-7
  // for best-effort scheduling. 0 is highest priority, 7 is lowest.
  int io_nice_level;

  int max_idle_secs;

  bool oom_more_eagerly;

  int oom_more_eagerly_threshold;

  bool write_command_log;

  // If true, Blaze will listen to OS-level file change notifications.
  bool watchfs;

  // Temporary experimental flag that permits configurable attribute syntax
  // in BUILD files. This will be removed when configurable attributes is
  // a more stable feature.
  bool allow_configurable_attributes;

  // Temporary flag for enabling EventBus exceptions to be fatal.
  bool fatal_event_bus_exceptions;

  // A string to string map specifying where each option comes from. If the
  // value is empty, it was on the command line, if it is a string, it comes
  // from a blazerc file, if a key is not present, it is the default.
  std::map<std::string, std::string> option_sources;

  // Returns the GetHostJavabase. This should be called after parsing
  // the --host_javabase option.
  std::string GetHostJavabase();

  // Port for gRPC command server. 0 means let the kernel choose, -1 means no
  // gRPC command server.
  int command_port;

  // Connection timeout for each gRPC connection attempt.
  int connect_timeout_secs;

  // Invocation policy proto. May be NULL.
  const char *invocation_policy;

  // Whether to output addition debugging information in the client.
  bool client_debug;

  // Whether to check custom file for exit code when the Blaze Server exits
  // abruptly without proper communication over gRPC.
  bool use_custom_exit_code_on_abrupt_exit;

 protected:
  // Constructor for subclasses only so that site-specific extensions of this
  // class can override the product name.  The product_name must be the
  // capitalized version of the name, as in "Bazel".
  explicit StartupOptions(const std::string &product_name,
                          const WorkspaceLayout* workspace_layout);

  // Holds the valid nullary startup options.
  std::vector<std::string> nullary_options;

  // Holds the valid unary startup options.
  std::vector<std::string> unary_options;

 private:
  std::string host_javabase;
};

}  // namespace blaze

#endif  // BAZEL_SRC_MAIN_CPP_STARTUP_OPTIONS_H_
