#!/bin/bash
#
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
#
# Tests the examples provided in Bazel
#

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

function write_hello_library_files() {
  mkdir -p java/main
  cat >java/main/BUILD <<EOF
java_binary(name = 'main',
    deps = ['//java/hello_library'],
    srcs = ['Main.java'],
    main_class = 'main.Main')
EOF

  cat >java/main/Main.java <<EOF
package main;
import hello_library.HelloLibrary;
public class Main {
  public static void main(String[] args) {
    HelloLibrary.funcHelloLibrary();
    System.out.println("Hello, World!");
  }
}
EOF

  mkdir -p java/hello_library
  cat >java/hello_library/BUILD <<EOF
package(default_visibility=['//visibility:public'])
java_library(name = 'hello_library',
             srcs = ['HelloLibrary.java']);
EOF

  cat >java/hello_library/HelloLibrary.java <<EOF
package hello_library;
public class HelloLibrary {
  public static void funcHelloLibrary() {
    System.out.print("Hello, Library!;");
  }
}
EOF
}

function test_build_hello_world() {
  write_hello_library_files

  bazel build //java/main:main &> $TEST_log || fail "build failed"
}

function test_errorprone_error_fails_build_by_default() {
  write_hello_library_files
  # Trigger an error-prone error by comparing two arrays via #equals().
  cat >java/hello_library/HelloLibrary.java <<EOF
package hello_library;
public class HelloLibrary {
  public static boolean funcHelloLibrary() {
    int[] arr1 = {1, 2, 3};
    int[] arr2 = {1, 2, 3};
    return arr1.equals(arr2);
  }
}
EOF

  bazel build //java/main:main &> $TEST_log && fail "build should have failed" || true
  expect_log "error: \[ArrayEquals\] Reference equality used to compare arrays"
}

function test_extrachecks_off_disables_errorprone() {
  write_hello_library_files
  # Trigger an error-prone error by comparing two arrays via #equals().
  cat >java/hello_library/HelloLibrary.java <<EOF
package hello_library;
public class HelloLibrary {
  public static boolean funcHelloLibrary() {
    int[] arr1 = {1, 2, 3};
    int[] arr2 = {1, 2, 3};
    return arr1.equals(arr2);
  }
}
EOF
  # Disable error-prone for this target, though.
  cat >java/hello_library/BUILD <<EOF
package(default_visibility=['//visibility:public'])
java_library(name = 'hello_library',
             srcs = ['HelloLibrary.java'],
             javacopts = ['-extra_checks:off'],);
EOF

  bazel build //java/main:main &> $TEST_log || fail "build failed"
  expect_not_log "error: \[ArrayEquals\] Reference equality used to compare arrays"
}

function test_java_test_main_class() {
  mkdir -p java/testrunners || fail "mkdir failed"
  cat > java/testrunners/TestRunner.java <<EOF
package testrunners;

import com.google.testing.junit.runner.BazelTestRunner;

public class TestRunner {
  public static void main(String[] argv) {
    System.out.println("Custom test runner was run");
    BazelTestRunner.main(argv);
  }
}
EOF

  cat > java/testrunners/Tests.java <<EOF
package testrunners;

import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.Test;

@RunWith(JUnit4.class)
public class Tests {

  @Test
  public void testTest() {
    System.out.println("testTest was run");
  }
}
EOF

  cat > java/testrunners/BUILD <<EOF
java_library(name = "test_runner",
             srcs = ['TestRunner.java'],
             deps = ['@bazel_tools//tools/jdk:TestRunner_deploy.jar'],
)

java_test(name = "Tests",
          srcs = ['Tests.java'],
          deps = ['@bazel_tools//tools/jdk:TestRunner_deploy.jar'],
          main_class = "testrunners.TestRunner",
          runtime_deps = [':test_runner']
)
EOF
  bazel test --test_output=streamed //java/testrunners:Tests &> "$TEST_log"
  expect_log "Custom test runner was run"
  expect_log "testTest was run"
}

function test_basic_java_sandwich() {
  mkdir -p java/com/google/sandwich
  cd java/com/google/sandwich

  touch BUILD A.java B.java C.java Main.java java_custom_library.bzl

  cat > BUILD << EOF
load(':java_custom_library.bzl', 'java_custom_library')

java_binary(
  name = "Main",
  srcs = ["Main.java"],
  deps = [":top"]
)

java_library(
  name = "top",
  srcs = ["A.java"],
  deps = [":middle"]
)

java_custom_library(
  name = "middle",
  srcs = ["B.java"],
  deps = [":bottom"]
)

java_library(
  name = "bottom",
  srcs = ["C.java"]
)
EOF

  cat > C.java << EOF
package com.google.sandwich;
class C {
  public void printC() {
    System.out.println("Message from C");
  }
}
EOF

  cat > B.java << EOF
package com.google.sandwich;
class B {
  C myObject;
  public void printB() {
    System.out.println("Message from B");
    myObject = new C();
    myObject.printC();
  }
}
EOF

  cat > A.java << EOF
package com.google.sandwich;
class A {
  B myObject;
  public void printA() {
    System.out.println("Message from A");
    myObject = new B();
    myObject.printB();
  }
}
EOF

  cat > Main.java << EOF
package com.google.sandwich;
class Main {
  public static void main(String[] args) {
    A myObject = new A();
    myObject.printA();
  }
}
EOF

  cat > java_custom_library.bzl << EOF
def _impl(ctx):
  deps = [dep[java_common.provider] for dep in ctx.attr.deps]
  deps_provider = java_common.merge(deps)

  output_jar = ctx.new_file("lib" + ctx.label.name + ".jar")

  compilation_provider = java_common.compile(
    ctx,
    source_files = ctx.files.srcs,
    output = output_jar,
    javac_opts = java_common.default_javac_opts(ctx, java_toolchain_attr = "_java_toolchain"),
    deps = deps,
    strict_deps = "ERROR",
    java_toolchain = ctx.attr._java_toolchain,
    host_javabase = ctx.attr._host_javabase
  )
  result = java_common.merge([deps_provider, compilation_provider])
  return struct(
    files = set([output_jar]),
    providers = [result]
  )

java_custom_library = rule(
  implementation = _impl,
  attrs = {
    "srcs": attr.label_list(allow_files=True),
    "deps": attr.label_list(),
    "_java_toolchain": attr.label(default = Label("@bazel_tools//tools/jdk:toolchain")),
    "_host_javabase": attr.label(default = Label("//tools/defaults:jdk"))
  },
  fragments = ["java"]
)
EOF

  $PRODUCT_NAME run :Main > $TEST_log || fail "Java sandwich build failed"
  expect_log "Message from A"
  expect_log "Message from B"
  expect_log "Message from C"
}

run_suite "Java integration tests"
