# Copyright 2016 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Rules for configuring the C++ toolchain (experimental)."""


def _get_value(it):
  """Convert `it` in serialized protobuf format."""
  if type(it) == "int":
    return str(it)
  elif type(it) == "bool":
    return "true" if it else "false"
  else:
    return "\"%s\"" % it


def _build_crosstool(d, prefix="  "):
  """Convert `d` to a string version of a CROSSTOOL file content."""
  lines = []
  for k in d:
    if type(d[k]) == "list":
      for it in d[k]:
        lines.append("%s%s: %s" % (prefix, k, _get_value(it)))
    else:
      lines.append("%s%s: %s" % (prefix, k, _get_value(d[k])))
  return "\n".join(lines)


def _build_tool_path(d):
  """Build the list of tool_path for the CROSSTOOL file."""
  lines = []
  for k in d:
    lines.append("  tool_path {name: \"%s\" path: \"%s\" }" % (k, d[k]))
  return "\n".join(lines)

def auto_configure_fail(msg):
  """Output failure message when auto configuration fails."""
  red = "\033[0;31m"
  no_color = "\033[0m"
  fail("\n%sAuto-Configuration Error:%s %s\n" % (red, no_color, msg))


def auto_configure_warning(msg):
  """Output warning message during auto configuration."""
  yellow = "\033[1;33m"
  no_color = "\033[0m"
  print("\n%sAuto-Configuration Warning:%s %s\n" % (yellow, no_color, msg))


def _get_env_var(repository_ctx, name, default = None):
  """Find an environment variable in system path."""
  if name in repository_ctx.os.environ:
    return repository_ctx.os.environ[name]
  if default != None:
    auto_configure_warning("'%s' environment variable is not set, using '%s' as default" % (name, default))
    return default
  auto_configure_fail("'%s' environment variable is not set" % name)


def _which(repository_ctx, cmd, default = None):
  """A wrapper around repository_ctx.which() to provide a fallback value."""
  result = repository_ctx.which(cmd)
  return default if result == None else str(result)


def _which_cmd(repository_ctx, cmd, default = None):
  """Find cmd in PATH using repository_ctx.which() and fail if cannot find it."""
  result = repository_ctx.which(cmd)
  if result != None:
    return str(result)
  path = _get_env_var(repository_ctx, "PATH")
  if default != None:
    auto_configure_warning("Cannot find %s in PATH, using '%s' as default.\nPATH=%s" % (cmd, default, path))
    return default
  auto_configure_fail("Cannot find %s in PATH, please make sure %s is installed and add its directory in PATH.\nPATH=%s" % (cmd, cmd, path))
  return str(result)


def _execute(repository_ctx, command, environment = None):
  """Execute a command, return stdout if succeed and throw an error if it fails."""
  if environment:
    result = repository_ctx.execute(command, environment = environment)
  else:
    result = repository_ctx.execute(command)
  if result.stderr:
    auto_configure_fail(result.stderr)
  else:
    return result.stdout.strip()


def _get_tool_paths(repository_ctx, darwin, cc):
  """Compute the path to the various tools."""
  return {k: _which(repository_ctx, k, "/usr/bin/" + k)
          for k in [
              "ld",
              "cpp",
              "dwp",
              "gcov",
              "nm",
              "objcopy",
              "objdump",
              "strip",
          ]} + {
              "gcc": cc,
              "ar": "/usr/bin/libtool"
                    if darwin else _which(repository_ctx, "ar", "/usr/bin/ar")
          }


def _cplus_include_paths(repository_ctx):
  """Use ${CPLUS_INCLUDE_PATH} to compute the list of flags for cxxflag."""
  if "CPLUS_INCLUDE_PATH" in repository_ctx.os.environ:
    result = []
    for p in repository_ctx.os.environ["CPLUS_INCLUDE_PATH"].split(":"):
      p = str(repository_ctx.path(p))  # Normalize the path
      result.append("-I" + p)
    return result
  else:
    return []


def _get_cpu_value(repository_ctx):
  """Compute the cpu_value based on the OS name."""
  os_name = repository_ctx.os.name.lower()
  if os_name.startswith("mac os"):
    return "darwin"
  if os_name.find("freebsd") != -1:
    return "freebsd"
  if os_name.find("windows") != -1:
    return "x64_windows"
  # Use uname to figure out whether we are on x86_32 or x86_64
  result = repository_ctx.execute(["uname", "-m"])
  if result.stdout.strip() in ["power", "ppc64le", "ppc"]:
    return "ppc"
  if result.stdout.strip() in ["arm", "armv7l"]:
    return "arm"
  if result.stdout.strip() in ["aarch64"]:
    return "aarch64"
  return "k8" if result.stdout.strip() in ["amd64", "x86_64", "x64"] else "piii"


_INC_DIR_MARKER_BEGIN = "#include <...>"

# OSX add " (framework directory)" at the end of line, strip it.
_OSX_FRAMEWORK_SUFFIX = " (framework directory)"
_OSX_FRAMEWORK_SUFFIX_LEN =  len(_OSX_FRAMEWORK_SUFFIX)
def _cxx_inc_convert(path):
  """Convert path returned by cc -E xc++ in a complete path."""
  path = path.strip()
  if path.endswith(_OSX_FRAMEWORK_SUFFIX):
    path = path[:-_OSX_FRAMEWORK_SUFFIX_LEN].strip()
  return path

def _get_cxx_inc_directories(repository_ctx, cc):
  """Compute the list of default C++ include directories."""
  result = repository_ctx.execute([cc, "-E", "-xc++", "-", "-v"])
  index1 = result.stderr.find(_INC_DIR_MARKER_BEGIN)
  if index1 == -1:
    return []
  index1 = result.stderr.find("\n", index1)
  if index1 == -1:
    return []
  index2 = result.stderr.rfind("\n ")
  if index2 == -1 or index2 < index1:
    return []
  index2 = result.stderr.find("\n", index2 + 1)
  if index2 == -1:
    inc_dirs = result.stderr[index1 + 1:]
  else:
    inc_dirs = result.stderr[index1 + 1:index2].strip()

  return [repository_ctx.path(_cxx_inc_convert(p))
          for p in inc_dirs.split("\n")]

def _add_option_if_supported(repository_ctx, cc, option):
  """Checks that `option` is supported by the C compiler."""
  result = repository_ctx.execute([
      cc,
      option,
      "-o",
      "/dev/null",
      "-c",
      str(repository_ctx.path("tools/cpp/empty.cc"))
  ])
  return [option] if result.stderr.find(option) == -1 else []

def _is_gold_supported(repository_ctx, cc):
  """Checks that `gold` is supported by the C compiler."""
  result = repository_ctx.execute([
      cc,
      "-fuse-ld=gold",
      "-o",
      "/dev/null",
      str(repository_ctx.path("tools/cpp/empty.cc"))
  ])
  return result.return_code == 0

def _crosstool_content(repository_ctx, cc, cpu_value, darwin):
  """Return the content for the CROSSTOOL file, in a dictionary."""
  supports_gold_linker = _is_gold_supported(repository_ctx, cc)
  return {
      "abi_version": "local",
      "abi_libc_version": "local",
      "builtin_sysroot": "",
      "compiler": "compiler",
      "host_system_name": "local",
      "needsPic": True,
      "supports_gold_linker": supports_gold_linker,
      "supports_incremental_linker": False,
      "supports_fission": False,
      "supports_interface_shared_objects": False,
      "supports_normalizing_ar": False,
      "supports_start_end_lib": supports_gold_linker,
      "target_libc": "macosx" if darwin else "local",
      "target_cpu": cpu_value,
      "target_system_name": "local",
      "cxx_flag": [
          "-std=c++0x",
      ] + _cplus_include_paths(repository_ctx),
      "linker_flag": [
          "-lstdc++",
          "-lm",  # Some systems expect -lm in addition to -lstdc++
          # Anticipated future default.
      ] + (
          ["-fuse-ld=gold"] if supports_gold_linker else []
      ) + _add_option_if_supported(
          repository_ctx, cc, "-Wl,-no-as-needed"
      ) + _add_option_if_supported(
          repository_ctx, cc, "-Wl,-z,relro,-z,now"
      ) + (
          [
              "-undefined",
              "dynamic_lookup",
              "-headerpad_max_install_names",
          ] if darwin else [
              "-B" + str(repository_ctx.path(cc).dirname),
              # Always have -B/usr/bin, see https://github.com/bazelbuild/bazel/issues/760.
              "-B/usr/bin",
              # Stamp the binary with a unique identifier.
              "-Wl,--build-id=md5",
              "-Wl,--hash-style=gnu"
              # Gold linker only? Can we enable this by default?
              # "-Wl,--warn-execstack",
              # "-Wl,--detect-odr-violations"
          ] + _add_option_if_supported(
              # Have gcc return the exit code from ld.
              repository_ctx, cc, "-pass-exit-codes"
          )
      ),
      "ar_flag": ["-static", "-s", "-o"] if darwin else [],
      "cxx_builtin_include_directory": _get_cxx_inc_directories(repository_ctx, cc),
      "objcopy_embed_flag": ["-I", "binary"],
      "unfiltered_cxx_flag":
          # If the compiler sometimes rewrites paths in the .d files without symlinks
          # (ie when they're shorter), it confuses Bazel's logic for verifying all
          # #included header files are listed as inputs to the action.
          _add_option_if_supported(repository_ctx, cc, "-fno-canonical-system-headers") + [
              # Make C++ compilation deterministic. Use linkstamping instead of these
              # compiler symbols.
              "-Wno-builtin-macro-redefined",
              "-D__DATE__=\\\"redacted\\\"",
              "-D__TIMESTAMP__=\\\"redacted\\\"",
              "-D__TIME__=\\\"redacted\\\""
          ],
      "compiler_flag": [
          # Security hardening requires optimization.
          # We need to undef it as some distributions now have it enabled by default.
          "-U_FORTIFY_SOURCE",
          "-fstack-protector",
          # All warnings are enabled. Maybe enable -Werror as well?
          "-Wall",
          # Enable a few more warnings that aren't part of -Wall.
      ] + (["-Wthread-safety", "-Wself-assign"] if darwin else [
          "-B" + str(repository_ctx.path(cc).dirname),
          # Always have -B/usr/bin, see https://github.com/bazelbuild/bazel/issues/760.
          "-B/usr/bin",
      ]) + (
          # Disable problematic warnings.
          _add_option_if_supported(repository_ctx, cc, "-Wunused-but-set-parameter") +
          # has false positives
          _add_option_if_supported(repository_ctx, cc, "-Wno-free-nonheap-object") +
          # Enable coloring even if there's no attached terminal. Bazel removes the
          # escape sequences if --nocolor is specified.
          _add_option_if_supported(repository_ctx, cc, "-fcolor-diagnostics")) + [
              # Keep stack frames for debugging, even in opt mode.
              "-fno-omit-frame-pointer",
          ],
  }

# TODO(pcloudy): Remove this after MSVC CROSSTOOL becomes default on Windows
def _get_windows_crosstool_content(repository_ctx):
  """Return the content of msys crosstool which is still the default CROSSTOOL on Windows."""
  msys_root = _execute(repository_ctx, ["cygpath", "-m", "/"])
  return (
      '   abi_version: "local"\n' +
      '   abi_libc_version: "local"\n' +
      '   builtin_sysroot: ""\n' +
      '   compiler: "windows_msys64"\n' +
      '   host_system_name: "local"\n' +
      "   needsPic: false\n" +
      '   target_libc: "local"\n' +
      '   target_cpu: "x64_windows"\n' +
      '   target_system_name: "local"\n' +
      '   tool_path { name: "ar" path: "%susr/bin/ar" }\n' % msys_root +
      '   tool_path { name: "compat-ld" path: "%susr/bin/ld" }\n' % msys_root +
      '   tool_path { name: "cpp" path: "%susr/bin/cpp" }\n' % msys_root +
      '   tool_path { name: "dwp" path: "%susr/bin/dwp" }\n' % msys_root +
      '   tool_path { name: "gcc" path: "%susr/bin/gcc" }\n' % msys_root +
      '   cxx_flag: "-std=gnu++0x"\n' +
      '   linker_flag: "-lstdc++"\n' +
      '   cxx_builtin_include_directory: "%s"\n' % msys_root +
      '   cxx_builtin_include_directory: "/usr/"\n' +
      '   tool_path { name: "gcov" path: "%susr/bin/gcov" }\n' % msys_root +
      '   tool_path { name: "ld" path: "%susr/bin/ld" }\n' % msys_root +
      '   tool_path { name: "nm" path: "%susr/bin/nm" }\n' % msys_root +
      '   tool_path { name: "objcopy" path: "%susr/bin/objcopy" }\n' % msys_root +
      '   objcopy_embed_flag: "-I"\n' +
      '   objcopy_embed_flag: "binary"\n' +
      '   tool_path { name: "objdump" path: "%susr/bin/objdump" }\n' % msys_root +
      '   tool_path { name: "strip" path: "%susr/bin/strip" }'% msys_root )

def _opt_content(darwin):
  """Return the content of the opt specific section of the CROSSTOOL file."""
  return {
      "compiler_flag": [
          # No debug symbols.
          # Maybe we should enable https://gcc.gnu.org/wiki/DebugFission for opt or
          # even generally? However, that can't happen here, as it requires special
          # handling in Bazel.
          "-g0",

          # Conservative choice for -O
          # -O3 can increase binary size and even slow down the resulting binaries.
          # Profile first and / or use FDO if you need better performance than this.
          "-O2",

          # Security hardening on by default.
          # Conservative choice; -D_FORTIFY_SOURCE=2 may be unsafe in some cases.
          "-D_FORTIFY_SOURCE=1",

          # Disable assertions
          "-DNDEBUG",

          # Removal of unused code and data at link time (can this increase binary size in some cases?).
          "-ffunction-sections",
          "-fdata-sections"
      ],
      "linker_flag": [] if darwin else ["-Wl,--gc-sections"]
  }


def _dbg_content():
  """Return the content of the dbg specific section of the CROSSTOOL file."""
  # Enable debug symbols
  return {"compiler_flag": "-g"}


def _get_system_root(repository_ctx):
  r"""Get System root path on Windows, default is C:\\Windows."""
  if "SYSTEMROOT" in repository_ctx.os.environ:
    return repository_ctx.os.environ["SYSTEMROOT"]
  auto_configure_warning("SYSTEMROOT is not set, using default SYSTEMROOT=C:\\Windows")
  return "C:\\Windows"

def _find_cc(repository_ctx):
  """Find the C++ compiler."""
  cc_name = "gcc"
  if "CC" in repository_ctx.os.environ:
    cc_name = repository_ctx.os.environ["CC"].strip()
    if not cc_name:
      cc_name = "gcc"
  if cc_name.startswith("/"):
    # Absolute path, maybe we should make this suported by our which function.
    return cc_name
  cc = repository_ctx.which(cc_name)
  if cc == None:
    fail(
        "Cannot find gcc, either correct your path or set the CC" +
        " environment variable")
  return cc


def _find_cuda(repository_ctx):
  """Find out if and where cuda is installed."""
  if "CUDA_PATH" in repository_ctx.os.environ:
    return repository_ctx.os.environ["CUDA_PATH"]
  nvcc = _which(repository_ctx, "nvcc.exe")
  if nvcc:
    return nvcc[:-len("/bin/nvcc.exe")]
  return None

def _find_python(repository_ctx):
  """Find where is python on Windows."""
  if "BAZEL_PYTHON" in repository_ctx.os.environ:
    python_binary = repository_ctx.os.environ["BAZEL_PYTHON"]
    if not python_binary.endswith(".exe"):
      python_binary = python_binary + ".exe"
    return python_binary
  auto_configure_warning("'BAZEL_PYTHON' is not set, start looking for python in PATH.")
  python_binary = _which_cmd(repository_ctx, "python.exe")
  auto_configure_warning("Python found at %s" % python_binary)
  return python_binary


def _find_vs_path(repository_ctx):
  """Find Visual Studio install path."""
  if "BAZEL_VS" in repository_ctx.os.environ:
    return repository_ctx.os.environ["BAZEL_VS"]
  auto_configure_warning("'BAZEL_VS' is not set, start looking for the latest Visual Studio installed.")

  # Query the installed visual stduios on the machine, should return something like:
  # HKEY_LOCAL_MACHINE\SOFTWARE\Wow6432Node\Microsoft\VisualStudio\14.0
  # HKEY_LOCAL_MACHINE\SOFTWARE\Wow6432Node\Microsoft\VisualStudio\8.0
  # ...
  reg_binary = _get_system_root(repository_ctx) + "\\system32\\reg.exe"
  result = _execute(repository_ctx, [reg_binary, "query", "HKEY_LOCAL_MACHINE\\SOFTWARE\\Wow6432Node\\Microsoft\\VisualStudio"])
  vs_versions = result.split("\n")

  vs_dir = None
  vs_version_number = -1
  for entry in vs_versions:
    entry = entry.strip()
    # Query InstallDir to find out where a specific version of VS is installed.
    # The output looks like if succeeded:
    # HKEY_LOCAL_MACHINE\SOFTWARE\Wow6432Node\Microsoft\VisualStudio\14.0
    #     InstallDir    REG_SZ    C:\Program Files (x86)\Microsoft Visual Studio 14.0\Common7\IDE\
    result = repository_ctx.execute([reg_binary, "query", entry, "-v", "InstallDir"])
    if not result.stderr:
      for line in result.stdout.split("\n"):
        line = line.strip()
        if line.startswith("InstallDir"):
          path_segments = [ seg for seg in line[len("InstallDir    REG_SZ    "):].split("\\") if seg ]
          # Extract version number X from "Microsoft Visual Studio X.0"
          version_number = int(path_segments[-3].split(" ")[-1][:-len(".0")])
          if version_number > vs_version_number:
            vs_version_number = version_number
            vs_dir = "\\".join(path_segments[:-2])

  if not vs_dir:
    auto_configure_fail("Visual Studio not found on your machine.")
  auto_configure_warning("Visual Studio found at %s" % vs_dir)
  return vs_dir


def _find_env_vars(repository_ctx, vs_path):
  """Get environment variables set by VCVARSALL.BAT."""
  vsvars = vs_path + "/VC/VCVARSALL.BAT"
  repository_ctx.file("wrapper/get_env.bat",
                      "@echo off\n" +
                      "call \"" + vsvars + "\" amd64 \n" +
                      "echo PATH=%PATH%,INCLUDE=%INCLUDE%,LIB=%LIB% \n", True)
  env = repository_ctx.os.environ
  if "PATH" not in env:
    env["PATH"]=""
  # Running VCVARSALL.BAT needs %SYSTEMROOT%\\system32 to be in PATH
  env["PATH"] = env["PATH"] + ";" + _get_system_root(repository_ctx) + "\\system32"
  envs = _execute(repository_ctx, ["wrapper/get_env.bat"], environment=env).split(",")
  env_map = {}
  for env in envs:
    key, value = env.split("=")
    env_map[key] = value.replace("\\", "\\\\")
  return env_map


def _is_support_whole_archive(repository_ctx, vs_dir):
  """Run MSVC linker alone to see if it supports /WHOLEARCHIVE."""
  env = repository_ctx.os.environ
  if "NO_WHOLE_ARCHIVE_OPTION" in env and env["NO_WHOLE_ARCHIVE_OPTION"] == "1":
    return False
  result = _execute(repository_ctx, [vs_dir + "/VC/BIN/amd64/link"])
  return result.find("/WHOLEARCHIVE") != -1


def _cuda_compute_capabilities(repository_ctx):
  """Returns a list of strings representing cuda compute capabilities."""

  if "CUDA_COMPUTE_CAPABILITIES" not in repository_ctx.os.environ:
    return ["3.5", "5.2"]
  capabilities_str = repository_ctx.os.environ["CUDA_COMPUTE_CAPABILITIES"]
  capabilities = capabilities_str.split(",")
  for capability in capabilities:
    # Workaround for Skylark's lack of support for regex. This check should
    # be equivalent to checking:
    #     if re.match("[0-9]+.[0-9]+", capability) == None:
    parts = capability.split(".")
    if len(parts) != 2 or not parts[0].isdigit() or not parts[1].isdigit():
      auto_configure_fail("Invalid compute capability: %s" % capability)
  return capabilities


def _tpl(repository_ctx, tpl, substitutions={}, out=None):
  if not out:
    out = tpl
  repository_ctx.template(
      out,
      Label("@bazel_tools//tools/cpp:%s.tpl" % tpl),
      substitutions)


def _get_env(repository_ctx):
  """Convert the environment in a list of export if in Homebrew."""
  env = repository_ctx.os.environ
  if "HOMEBREW_RUBY_PATH" in env:
    return "\n".join([
        "export %s='%s'" % (k, env[k].replace("'", "'\\''"))
        for k in env
        if k != "_" and k.find(".") == -1
    ])
  else:
    return ""

def _impl(repository_ctx):
  repository_ctx.file("tools/cpp/empty.cc", "int main() {}")
  cpu_value = _get_cpu_value(repository_ctx)
  if cpu_value == "freebsd":
    # This is defaulting to the static crosstool, we should eventually do those platform too.
    # Theorically, FreeBSD should be straightforward to add but we cannot run it in a docker
    # container so escaping until we have proper tests for FreeBSD.
    repository_ctx.symlink(Label("@bazel_tools//tools/cpp:CROSSTOOL"), "CROSSTOOL")
    repository_ctx.symlink(Label("@bazel_tools//tools/cpp:BUILD.static"), "BUILD")
  elif cpu_value == "x64_windows":
    repository_ctx.symlink(Label("@bazel_tools//tools/cpp:BUILD.static"), "BUILD")

    msvc_wrapper = repository_ctx.path(Label("@bazel_tools//tools/cpp:CROSSTOOL")).dirname.get_child("wrapper").get_child("bin")
    for f in ["msvc_cl.bat", "msvc_link.bat", "msvc_nop.bat"]:
      repository_ctx.symlink(msvc_wrapper.get_child(f), "wrapper/bin/" + f)
    msvc_wrapper = msvc_wrapper.get_child("pydir")
    for f in ["msvc_cl.py", "msvc_link.py"]:
      repository_ctx.symlink(msvc_wrapper.get_child(f), "wrapper/bin/pydir/" + f)

    python_binary = _find_python(repository_ctx)
    _tpl(repository_ctx, "wrapper/bin/call_python.bat", {"%{python_binary}": python_binary})

    vs_path = _find_vs_path(repository_ctx)
    env = _find_env_vars(repository_ctx, vs_path)
    python_dir = python_binary[0:-10].replace("\\", "\\\\")
    include_paths = env["INCLUDE"] + (python_dir + "include")
    lib_paths = env["LIB"] + (python_dir + "libs")
    lib_tool = vs_path.replace("\\", "\\\\") + "/VC/bin/amd64/lib.exe"
    if _is_support_whole_archive(repository_ctx, vs_path):
      support_whole_archive = "True"
    else:
      support_whole_archive = "False"
    tmp_dir = _get_env_var(repository_ctx, "TMP", "C:\\Windows\\Temp").replace("\\", "\\\\")
    # Make sure nvcc.exe is in PATH
    paths = env["PATH"]
    cuda_path = _find_cuda(repository_ctx)
    if cuda_path:
      paths = (cuda_path.replace("\\", "\\\\") + "/bin;") + paths
    compute_capabilities = _cuda_compute_capabilities(repository_ctx)
    _tpl(repository_ctx, "wrapper/bin/pydir/msvc_tools.py", {
        "%{tmp}": tmp_dir,
        "%{path}": paths,
        "%{include}": include_paths,
        "%{lib}": lib_paths,
        "%{lib_tool}": lib_tool,
        "%{support_whole_archive}": support_whole_archive,
        "%{cuda_compute_capabilities}": ", ".join(
            ["\"%s\"" % c for c in compute_capabilities]),
    })

    # nvcc will generate some source files under tmp_dir
    cxx_include_directories = [ "cxx_builtin_include_directory: \"%s\"" % tmp_dir ]
    for path in include_paths.split(";"):
      if path:
        cxx_include_directories.append(("cxx_builtin_include_directory: \"%s\"" % path))
    _tpl(repository_ctx, "CROSSTOOL", {
        "%{cpu}": cpu_value,
        "%{content}": _get_windows_crosstool_content(repository_ctx),
        "%{opt_content}": "",
        "%{dbg_content}": "",
        "%{cxx_builtin_include_directory}": "\n".join(cxx_include_directories),
    })
  else:
    darwin = cpu_value == "darwin"
    cc = _find_cc(repository_ctx)
    tool_paths = _get_tool_paths(repository_ctx, darwin,
                                 "cc_wrapper.sh" if darwin else str(cc))
    crosstool_content = _crosstool_content(repository_ctx, cc, cpu_value, darwin)
    opt_content = _opt_content(darwin)
    dbg_content = _dbg_content()
    _tpl(repository_ctx, "BUILD", {
        "%{name}": cpu_value,
        "%{supports_param_files}": "0" if darwin else "1",
        "%{cc_compiler_deps}": ":cc_wrapper" if darwin else ":empty",
    })
    _tpl(repository_ctx,
        "osx_cc_wrapper.sh" if darwin else "linux_cc_wrapper.sh",
        {"%{cc}": str(cc), "%{env}": _get_env(repository_ctx)},
        "cc_wrapper.sh")
    _tpl(repository_ctx, "CROSSTOOL", {
        "%{cpu}": cpu_value,
        "%{content}": _build_crosstool(crosstool_content) + "\n" +
                      _build_tool_path(tool_paths),
        "%{opt_content}": _build_crosstool(opt_content, "    "),
        "%{dbg_content}": _build_crosstool(dbg_content, "    "),
        "%{cxx_builtin_include_directory}": "",
    })


cc_autoconf = repository_rule(implementation=_impl, local=True)


def cc_configure():
  """A C++ configuration rules that generate the crosstool file."""
  cc_autoconf(name="local_config_cc")
  native.bind(name="cc_toolchain", actual="@local_config_cc//:toolchain")
