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

package com.google.devtools.build.lib.actions;

/** Helper methods relating to implementations of {@link Spawn}. */
public final class Spawns {
  private Spawns() {}

  /**
   * Parse the timeout key in the spawn execution info, if it exists. Return -1 if the key does not
   * exist.
   */
  public static int getTimeoutSeconds(Spawn spawn) throws ExecException {
    return getTimeoutSeconds(spawn, -1);
  }

  /**
   * Parse the timeout key in the spawn execution info, if it exists. Otherwise, return the given
   * default timeout.
   */
  public static int getTimeoutSeconds(Spawn spawn, int defaultTimeout) throws ExecException {
    String timeoutStr = spawn.getExecutionInfo().get("timeout");
    if (timeoutStr == null) {
      return defaultTimeout;
    }
    try {
      return Integer.parseInt(timeoutStr);
    } catch (NumberFormatException e) {
      throw new UserExecException("could not parse timeout: ", e);
    }
  }
}
