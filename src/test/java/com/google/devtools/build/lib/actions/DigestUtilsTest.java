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
package com.google.devtools.build.lib.actions;


import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Strings;
import com.google.devtools.build.lib.actions.cache.DigestUtils;
import com.google.devtools.build.lib.testutil.TestThread;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.util.BlazeClock;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystem.HashFunction;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests for DigestUtils.
 */
@RunWith(JUnit4.class)
public class DigestUtilsTest {

  private static void assertDigestCalculationConcurrency(boolean expectConcurrent,
      final boolean fastDigest, final int fileSize1, final int fileSize2,
      HashFunction hf) throws Exception {
    final CountDownLatch barrierLatch = new CountDownLatch(2); // Used to block test threads.
    final CountDownLatch readyLatch = new CountDownLatch(1);   // Used to block main thread.

    FileSystem myfs = new InMemoryFileSystem(BlazeClock.instance()) {
        @Override
        protected byte[] getMD5Digest(Path path) throws IOException {
          try {
            barrierLatch.countDown();
            readyLatch.countDown();
            // Either both threads will be inside getMD5Digest at the same time or they
            // both will be blocked.
            barrierLatch.await();
          } catch (Exception e) {
            throw new IOException(e);
          }
          return super.getMD5Digest(path);
        }

        @Override
        protected byte[] getSHA1Digest(Path path) throws IOException {
          try {
            barrierLatch.countDown();
            readyLatch.countDown();
            // Either both threads will be inside getSHA1Digest at the same time or they
            // both will be blocked.
            barrierLatch.await();
          } catch (Exception e) {
            throw new IOException(e);
          }
          return super.getSHA1Digest(path);
        }

        @Override
        protected byte[] getFastDigest(Path path, HashFunction hashFunction) throws IOException {
          return fastDigest ? super.getDigest(path, hashFunction) : null;
        }
    };

    FileSystem.setDigestFunctionForTesting(hf);
    final Path myFile1 = myfs.getPath("/f1.dat");
    final Path myFile2 = myfs.getPath("/f2.dat");
    FileSystemUtils.writeContentAsLatin1(myFile1, Strings.repeat("a", fileSize1));
    FileSystemUtils.writeContentAsLatin1(myFile2, Strings.repeat("b", fileSize2));

     TestThread thread1 = new TestThread () {
       @Override public void runTest() throws Exception {
         DigestUtils.getDigestOrFail(myFile1, fileSize1);
       }
     };

     TestThread thread2 = new TestThread () {
       @Override public void runTest() throws Exception {
         DigestUtils.getDigestOrFail(myFile2, fileSize2);
       }
     };

     thread1.start();
     thread2.start();
     if (!expectConcurrent) { // Synchronized case.
       // Wait until at least one thread reached getMD5Digest().
       assertTrue(readyLatch.await(TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
       // Only 1 thread should be inside getMD5Digest().
       assertEquals(1, barrierLatch.getCount());
       barrierLatch.countDown(); // Release barrier latch, allowing both threads to proceed.
     }
     // Test successful execution within 5 seconds.
     thread1.joinAndAssertState(TestUtils.WAIT_TIMEOUT_MILLISECONDS);
     thread2.joinAndAssertState(TestUtils.WAIT_TIMEOUT_MILLISECONDS);
  }

  /**
   * Ensures that MD5 calculation is synchronized for files
   * greater than 4096 bytes if MD5 is not available cheaply,
   * so machines with rotating drives don't become unusable.
   */
  @Test
  public void testCalculationConcurrency() throws Exception {
    for (HashFunction hf : Arrays.asList(HashFunction.MD5, HashFunction.SHA1)) {
      assertDigestCalculationConcurrency(true, true, 4096, 4096, hf);
      assertDigestCalculationConcurrency(true, true, 4097, 4097, hf);
      assertDigestCalculationConcurrency(true, false, 4096, 4096, hf);
      assertDigestCalculationConcurrency(false, false, 4097, 4097, hf);
      assertDigestCalculationConcurrency(true, false, 1024, 4097, hf);
      assertDigestCalculationConcurrency(true, false, 1024, 1024, hf);
    }
  }

  @Test
  public void testRecoverFromMalformedDigest() throws Exception {
    final byte[] malformed = {0, 0, 0};
    FileSystem myFS = new InMemoryFileSystem(BlazeClock.instance()) {
      @Override
      protected byte[] getFastDigest(Path path, HashFunction hashFunction) throws IOException {
        // Digest functions have more than 3 bytes, usually at least 16.
        return malformed;
      }
    };
    Path path = myFS.getPath("/file");
    FileSystemUtils.writeContentAsLatin1(path, "a");
    for (HashFunction hf : Arrays.asList(HashFunction.MD5, HashFunction.SHA1)) {
      FileSystem.setDigestFunctionForTesting(hf);
      byte[] result = DigestUtils.getDigestOrFail(path, 1);
      assertArrayEquals(path.getDigest(), result);
      assertNotSame(malformed, result);
      assertTrue(path.isValidDigest(result));
    }
  }
}
