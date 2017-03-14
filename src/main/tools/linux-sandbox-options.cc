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

#include "linux-sandbox-options.h"
#include "linux-sandbox-utils.h"

#define DIE(args...)                                     \
  {                                                      \
    fprintf(stderr, __FILE__ ":" S__LINE__ ": \"" args); \
    fprintf(stderr, "\": ");                             \
    perror(NULL);                                        \
    exit(EXIT_FAILURE);                                  \
  }

#include <errno.h>
#include <sched.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

#include <fstream>
#include <iostream>
#include <memory>
#include <string>
#include <vector>

using std::ifstream;
using std::unique_ptr;
using std::vector;

struct Options opt;

// Print out a usage error. argc and argv are the argument counter and vector,
// fmt is a format, string for the error message to print.
static void Usage(char *program_name, const char *fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  vfprintf(stderr, fmt, ap);
  va_end(ap);

  fprintf(stderr, "\nUsage: %s -- command arg1 @args\n", program_name);
  fprintf(stderr,
          "\nPossible arguments:\n"
          "  -W <working-dir>  working directory (uses current directory if "
          "not specified)\n"
          "  -T <timeout>  timeout after which the child process will be "
          "terminated with SIGTERM\n"
          "  -t <timeout>  in case timeout occurs, how long to wait before "
          "killing the child with SIGKILL\n"
          "  -l <file>  redirect stdout to a file\n"
          "  -L <file>  redirect stderr to a file\n"
          "  -w <file>  make a file or directory writable for the sandboxed "
          "process\n"
          "  -i <file>  make a file or directory inaccessible for the "
          "sandboxed process\n"
          "  -e <dir>  mount an empty tmpfs on a directory\n"
          "  -M/-m <source/target>  directory to mount inside the sandbox\n"
          "    Multiple directories can be specified and each of them will be "
          "mounted readonly.\n"
          "    The -M option specifies which directory to mount, the -m option "
          "specifies where to\n"
          "  -N  if set, a new network namespace will be created\n"
          "  -R  if set, make the uid/gid be root, otherwise use nobody\n"
          "  -D  if set, debug info will be printed\n"
          "  @FILE  read newline-separated arguments from FILE\n"
          "  --  command to run inside sandbox, followed by arguments\n");
  exit(EXIT_FAILURE);
}

// Child function used by CheckNamespacesSupported() in call to clone().
static int CheckNamespacesSupportedChild(void *arg) { return 0; }

// Check whether the required namespaces are supported.
static int CheckNamespacesSupported() {
  const int kStackSize = 1024 * 1024;
  vector<char> child_stack(kStackSize);

  pid_t pid = clone(CheckNamespacesSupportedChild, &child_stack.back(),
                    CLONE_NEWUSER | CLONE_NEWNS | CLONE_NEWUTS | CLONE_NEWIPC |
                        CLONE_NEWNET | CLONE_NEWPID | SIGCHLD,
                    NULL);
  if (pid < 0) {
    DIE("pid");
  }

  int err;
  do {
    err = waitpid(pid, NULL, 0);
  } while (err < 0 && errno == EINTR);

  if (err < 0) {
    DIE("waitpid");
  }

  return EXIT_SUCCESS;
}

static void ValidateIsAbsolutePath(char *path, char *program_name, char flag) {
  if (path[0] != '/') {
    Usage(program_name, "The -%c option must be used with absolute paths only.",
          flag);
  }
}

// Parses command line flags from an argv array and puts the results into an
// Options structure passed in as an argument.
static void ParseCommandLine(unique_ptr<vector<char *>> args) {
  extern char *optarg;
  extern int optind, optopt;
  int c;
  bool source_specified;

  while ((c = getopt(args->size(), args->data(),
                     ":CS:W:T:t:l:L:w:i:e:M:m:NRD")) != -1) {
    if (c != 'M' && c != 'm') source_specified = false;
    switch (c) {
      case 'C':
        // Shortcut for the "does this system support sandboxing" check.
        exit(CheckNamespacesSupported());
        break;
      case 'S':
        if (opt.sandbox_root_dir == NULL) {
          ValidateIsAbsolutePath(optarg, args->front(), static_cast<char>(c));
          opt.sandbox_root_dir = strdup(optarg);
        } else {
          Usage(args->front(),
                "Multiple root directories (-S) specified, expected one.");
        }
        break;
      case 'W':
        if (opt.working_dir == NULL) {
          ValidateIsAbsolutePath(optarg, args->front(), static_cast<char>(c));
          opt.working_dir = strdup(optarg);
        } else {
          Usage(args->front(),
                "Multiple working directories (-W) specified, expected one.");
        }
        break;
      case 'T':
        if (sscanf(optarg, "%d", &opt.timeout_secs) != 1 ||
            opt.timeout_secs < 0) {
          Usage(args->front(), "Invalid timeout (-T) value: %s", optarg);
        }
        break;
      case 't':
        if (sscanf(optarg, "%d", &opt.kill_delay_secs) != 1 ||
            opt.kill_delay_secs < 0) {
          Usage(args->front(), "Invalid kill delay (-t) value: %s", optarg);
        }
        break;
      case 'l':
        if (opt.stdout_path == NULL) {
          opt.stdout_path = optarg;
        } else {
          Usage(args->front(),
                "Cannot redirect stdout to more than one destination.");
        }
        break;
      case 'L':
        if (opt.stderr_path == NULL) {
          opt.stderr_path = optarg;
        } else {
          Usage(args->front(),
                "Cannot redirect stderr to more than one destination.");
        }
        break;
      case 'w':
        ValidateIsAbsolutePath(optarg, args->front(), static_cast<char>(c));
        opt.writable_files.push_back(strdup(optarg));
        break;
      case 'i':
        ValidateIsAbsolutePath(optarg, args->front(), static_cast<char>(c));
        opt.inaccessible_files.push_back(strdup(optarg));
        break;
      case 'e':
        ValidateIsAbsolutePath(optarg, args->front(), static_cast<char>(c));
        opt.tmpfs_dirs.push_back(strdup(optarg));
        break;
      case 'M':
        ValidateIsAbsolutePath(optarg, args->front(), static_cast<char>(c));
        // Add the current source path to both source and target lists
        opt.bind_mount_sources.push_back(strdup(optarg));
        opt.bind_mount_targets.push_back(strdup(optarg));
        source_specified = true;
        break;
      case 'm':
        ValidateIsAbsolutePath(optarg, args->front(), static_cast<char>(c));
        if (!source_specified) {
          Usage(args->front(),
                "The -m option must be strictly preceded by an -M option.");
        }
        opt.bind_mount_targets.pop_back();
        opt.bind_mount_targets.push_back(strdup(optarg));
        source_specified = false;
        break;
      case 'N':
        opt.create_netns = true;
        break;
      case 'R':
        opt.fake_root = true;
        break;
      case 'D':
        opt.debug = true;
        break;
      case '?':
        Usage(args->front(), "Unrecognized argument: -%c (%d)", optopt, optind);
        break;
      case ':':
        Usage(args->front(), "Flag -%c requires an argument", optopt);
        break;
    }
  }

  if (optind < static_cast<int>(args->size())) {
    if (opt.args.empty()) {
      opt.args.assign(args->begin() + optind, args->end());
    } else {
      Usage(args->front(), "Merging commands not supported.");
    }
  }
}

// Expands a single argument, expanding options @filename to read in the content
// of the file and add it to the list of processed arguments.
unique_ptr<vector<char *>> ExpandArgument(unique_ptr<vector<char *>> expanded,
                                          char *arg) {
  if (arg[0] == '@') {
    const char *filename = arg + 1;  // strip off the '@'.
    ifstream f(filename);

    if (!f.is_open()) {
      DIE("opening argument file %s failed", filename);
    }

    for (std::string line; std::getline(f, line);) {
      if (line.length() > 0) {
        expanded = ExpandArgument(std::move(expanded), strdup(line.c_str()));
      }
    }

    if (f.bad()) {
      DIE("error while reading from argument file %s", filename);
    }
  } else {
    expanded->push_back(arg);
  }

  return expanded;
}

// Pre-processes an argument list, expanding options @filename to read in the
// content of the file and add it to the list of arguments. Stops expanding
// arguments once it encounters "--".
unique_ptr<vector<char *>> ExpandArguments(const vector<char *> &args) {
  unique_ptr<vector<char *>> expanded(new vector<char *>());
  expanded->reserve(args.size());
  for (auto arg = args.begin(); arg != args.end(); ++arg) {
    if (strcmp(*arg, "--") != 0) {
      expanded = ExpandArgument(std::move(expanded), *arg);
    } else {
      expanded->insert(expanded->end(), arg, args.end());
      break;
    }
  }
  return expanded;
}

// Handles parsing all command line flags and populates the global opt struct.
void ParseOptions(int argc, char *argv[]) {
  vector<char *> args(argv, argv + argc);
  ParseCommandLine(ExpandArguments(args));

  if (opt.args.empty()) {
    Usage(args.front(), "No command specified.");
  }

  opt.tmpfs_dirs.push_back("/tmp");

  if (opt.working_dir == NULL) {
    opt.working_dir = getcwd(NULL, 0);
  }
}
