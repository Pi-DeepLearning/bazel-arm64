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
# Correctness tests for using a Persistent TestRunner.
#

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

function DISABLED_test_simple_scenario() {
  mkdir -p java/testrunners || fail "mkdir failed"

  cat > java/testrunners/TestsPass.java <<EOF
package testrunners;

import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.Test;

@RunWith(JUnit4.class)
public class TestsPass {

  @Test
  public void testPass() {
    // This passes
  }
}
EOF

  cat > java/testrunners/TestsFail.java <<EOF
package testrunners;
import static org.junit.Assert.fail;

import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.Test;

@RunWith(JUnit4.class)
public class TestsFail {

  @Test
  public void testFail() {
    fail("Test is supposed to fail");
  }
}
EOF

  cat > java/testrunners/BUILD <<EOF
java_test(name = "TestsPass",
          srcs = ['TestsPass.java'],
          deps = ['@bazel_tools//tools/jdk:TestRunner_deploy.jar'],
)

java_test(name = "TestsFail",
          srcs = ['TestsFail.java'],
          deps = ['@bazel_tools//tools/jdk:TestRunner_deploy.jar'],
)
EOF

  bazel test --test_strategy=experimental_worker //java/testrunners:TestsPass \
      || fail "Test fails unexpectedly"

  bazel test --test_strategy=experimental_worker //java/testrunners:TestsFail \
      && fail "Test passes unexpectedly" \
      || true
}

# TODO(kush): Enable this test once we're able to reload modified classes in persistent test runner.
function DISABLED_test_reload_modified_classes() {
  mkdir -p java/testrunners || fail "mkdir failed"

  # Create a passing test.
  cat > java/testrunners/Tests.java <<EOF
package testrunners;

import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.Test;

@RunWith(JUnit4.class)
public class Tests {

  @Test
  public void testPass() {
    // This passes
  }
}
EOF

  cat > java/testrunners/BUILD <<EOF
java_test(name = "Tests",
          srcs = ['Tests.java'],
          deps = ['@bazel_tools//tools/jdk:TestRunner_deploy.jar'],
)
EOF

  bazel test --test_strategy=experimental_worker //java/testrunners:Tests &> $TEST_log \
      || fail "Test fails unexpectedly"

  # Now get the test to fail.
  cat > java/testrunners/Tests.java <<EOF
package testrunners;
import static org.junit.Assert.fail;

import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.Test;

@RunWith(JUnit4.class)
public class Tests {

  @Test
  public void testPass() {
    fail("Test is supposed to fail now");
  }
}
EOF

  bazel test --test_strategy=experimental_worker //java/testrunners:Tests &> $TEST_log \
      && fail "Test passes unexpectedly" \
      || true
}

# TODO(kush): Remove this fake test once we enable real tests
function test_placeholder_until_real_tests_are_enabled() {
  echo "test_placeholder_until_real_tests_are_enabled"
}

run_suite "Persistent Test Runner tests"
