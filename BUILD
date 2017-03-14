package(default_visibility = ["//scripts/release:__pkg__"])

filegroup(
    name = "git",
    srcs = glob([".git/**"]),
)

filegroup(
    name = "dummy",
    visibility = ["//visibility:public"],
)

filegroup(
    name = "workspace-file",
    srcs = [":WORKSPACE"],
    visibility = [
        "//src/test/shell/bazel:__subpackages__",
        "//tools/cpp/test:__pkg__",
    ],
)

filegroup(
    name = "changelog-file",
    srcs = [":CHANGELOG.md"],
    visibility = [
        "//scripts/packages:__pkg__",
    ],
)

filegroup(
    name = "srcs",
    srcs = glob(
        ["*"],
        exclude = [
            "bazel-*",  # convenience symlinks
            "out",  # IntelliJ with setup-intellij.sh
            "output",  # output of compile.sh
            "WORKSPACE.user.bzl",  # generated workspace file
            ".*",  # mainly .git* files
        ],
    ) + [
        "//examples:srcs",
        "//scripts:srcs",
        "//site:srcs",
        "//src:srcs",
        "//tools:srcs",
        "//third_party:srcs",
    ],
    visibility = ["//visibility:private"],
)

load("//tools/build_defs/pkg:pkg.bzl", "pkg_tar")

pkg_tar(
    name = "bazel-srcs",
    files = [":srcs"],
    strip_prefix = ".",
    # Public but bazel-only visibility.
    visibility = ["//:__subpackages__"],
)

genrule(
    name = "bazel-distfile",
    srcs = [
        ":bazel-srcs",
        "//src:derived_java_srcs",
    ],
    outs = ["bazel-distfile.zip"],
    cmd = "$(location :combine_distfiles.sh) $@ $(SRCS)",
    tools = ["combine_distfiles.sh"],
    # Public but bazel-only visibility.
    visibility = ["//:__subpackages__"],
)

genrule(
    name = "bazel-distfile-tar",
    srcs = [
        ":bazel-srcs",
        "//src:derived_java_srcs",
    ],
    outs = ["bazel-distfile.tar"],
    cmd = "env USE_TAR=YES $(location :combine_distfiles.sh) $@ $(SRCS)",
    tools = ["combine_distfiles.sh"],
    # Public but bazel-only visibility.
    visibility = ["//:__subpackages__"],
)
