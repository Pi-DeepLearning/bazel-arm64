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
"""Repository rule to generate host xcode_config and xcode_version targets.

   The xcode_config and xcode_version targets are configured for xcodes/SDKs
   installed on the local host.
"""


def _search_string(fullstring, prefix, suffix):
  """Returns the substring between two given substrings of a larger string.

  Args:
    fullstring: The larger string to search.
    prefix: The substring that should occur directly before the returned string.
    suffix: The substring that should occur direclty after the returned string.
  Returns:
    A string occurring in fullstring exactly prefixed by prefix, and exactly
    terminated by suffix. For example, ("hello goodbye", "lo ", " bye") will
    return "good". If there is no such string, returns the empty string.
  """

  prefix_index = fullstring.find(prefix)
  if (prefix_index < 0):
    return ""
  result_start_index = prefix_index + len(prefix)
  suffix_index = fullstring.find(suffix, result_start_index)
  if (suffix_index < 0):
    return ""
  return fullstring[result_start_index:suffix_index]


def _search_sdk_output(output, sdkname):
  """Returns the sdk version given xcodebuild stdout and an sdkname."""
  return _search_string(output, "(%s" % sdkname, ")")


def _xcode_version_output(repository_ctx, name, version, aliases, developer_dir):
  """Returns a string containing an xcode_version build target."""
  build_contents = ""
  decorated_aliases = []
  for alias in aliases:
    decorated_aliases.append("'%s'" % alias)
  xcodebuild_result = repository_ctx.execute(["xcodebuild", "-version", "-sdk"], 5, {"DEVELOPER_DIR": developer_dir})
  if (xcodebuild_result.return_code != 0):
    print(
        "Invoking xcodebuild failed, return code {code}, stderr: {err}".format(
            code=xcodebuild_result.return_code,
            err=xcodebuild_result.stderr))
  ios_sdk_version = _search_sdk_output(xcodebuild_result.stdout, "iphoneos")
  tvos_sdk_version = _search_sdk_output(xcodebuild_result.stdout, "appletvos")
  macosx_sdk_version = _search_sdk_output(xcodebuild_result.stdout, "macosx")
  watchos_sdk_version = _search_sdk_output(xcodebuild_result.stdout, "watchos")
  build_contents += "xcode_version(\n  name = '%s'," % name
  build_contents += "\n  version = '%s'," % version
  if aliases:
    build_contents += "\n  aliases = [%s]," % " ,".join(decorated_aliases)
  if ios_sdk_version:
    build_contents += "\n  default_ios_sdk_version = '%s'," % ios_sdk_version
  if tvos_sdk_version:
    build_contents += "\n  default_tvos_sdk_version = '%s'," % tvos_sdk_version
  if macosx_sdk_version:
    build_contents += "\n  default_macosx_sdk_version = '%s'," % macosx_sdk_version
  if watchos_sdk_version:
    build_contents += "\n  default_watchos_sdk_version = '%s'," % watchos_sdk_version
  build_contents += "\n)\n"
  return build_contents


VERSION_CONFIG_STUB = "xcode_config(name = 'host_xcodes')"


def _darwin_build_file(repository_ctx):
  """Evaluates local system state to create xcode_config and xcode_version targets."""
  xcodebuild_result = repository_ctx.execute(["xcodebuild", "-version"])
  # "xcodebuild -version" failing may be indicative of no versions of xcode
  # installed, which is an acceptable machine configuration to have for using
  # bazel. Thus no warning should be emitted here.
  if (xcodebuild_result.return_code != 0):
    return VERSION_CONFIG_STUB

  xcodeloc_src_path = str(repository_ctx.path(Label(repository_ctx.attr.xcode_locator)))
  xcrun_result = repository_ctx.execute(["xcrun", "clang", "-fobjc-arc", "-framework",
                                         "CoreServices", "-framework", "Foundation", "-o",
                                         "xcode-locator-bin", xcodeloc_src_path],
                                        5, {"SDKROOT": "", "IPHONEOS_DEPLOYMENT_TARGET": ""})

  if (xcrun_result.return_code != 0):
    print(
        "Generating xcode-locator-bin failed, return code {code}, stderr: {err}".format(
            code=xcrun_result.return_code,
            err=xcrun_result.stderr))
    return VERSION_CONFIG_STUB

  xcode_locator_result = repository_ctx.execute(["./xcode-locator-bin", "-v"])
  if (xcode_locator_result.return_code != 0):
    print(
        "Invoking xcode-locator failed, return code {code}, stderr: {err}".format(
            code=xcode_locator_result.return_code,
            err=xcode_locator_result.stderr))
    return VERSION_CONFIG_STUB

  default_xcode_version = _search_string(xcodebuild_result.stdout, "Xcode ", "\n")
  default_xcode_target = ""
  target_names = []
  buildcontents = ""

  # xcode_dump is comprised of newlines with different installed xcode versions,
  # each line of the form <version>:<comma_separated_aliases>:<developer_dir>.
  xcode_dump = xcode_locator_result.stdout
  for xcodeversion in xcode_dump.split("\n"):
    if ":" in xcodeversion:
      infosplit = xcodeversion.split(":")
      version = infosplit[0]
      aliases = infosplit[1].split(",")
      developer_dir = infosplit[2]
      target_name = "version%s" % version.replace(".", "_")
      buildcontents += _xcode_version_output(repository_ctx, target_name, version, aliases, developer_dir)
      target_names.append("':%s'" % target_name)
      if (version == default_xcode_version or default_xcode_version in aliases):
        default_xcode_target = target_name
  buildcontents += "xcode_config(name = 'host_xcodes',"
  if target_names:
    buildcontents += "\n  versions = [%s]," % ", ".join(target_names)
  if default_xcode_target:
    buildcontents += "\n  default = ':%s'," % default_xcode_target
  buildcontents += "\n)\n"
  return buildcontents


def _impl(repository_ctx):
  """Implementation for the local_config_xcode repository rule.

  Generates a BUILD file containing a root xcode_config target named 'host_xcodes',
  which points to an xcode_version target for each version of xcode installed on
  the local host machine. If no versions of xcode are present on the machine
  (for instance, if this is a non-darwin OS), creates a stub target.

  Args:
    repository_ctx: The repository context.
  """

  os_name = repository_ctx.os.name.lower()
  build_contents = "package(default_visibility = ['//visibility:public'])\n\n"
  if (os_name.startswith("mac os")):
    build_contents += _darwin_build_file(repository_ctx)
  else:
    build_contents += VERSION_CONFIG_STUB
  repository_ctx.file("BUILD", build_contents)

xcode_autoconf = repository_rule(
    implementation=_impl,
    local=True,
    attrs={
        "xcode_locator": attr.string(),
    }
)


def xcode_configure(xcode_locator_label):
  """Generates a repository containing host xcode version information."""
  xcode_autoconf(
      name="local_config_xcode",
      xcode_locator=xcode_locator_label
  )
