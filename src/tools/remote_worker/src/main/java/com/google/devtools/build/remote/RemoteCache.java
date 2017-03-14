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

package com.google.devtools.build.remote;

import com.google.devtools.build.lib.remote.CacheNotFoundException;
import com.google.devtools.build.lib.remote.CasServiceGrpc.CasServiceImplBase;
import com.google.devtools.build.lib.remote.ConcurrentMapActionCache;
import com.google.devtools.build.lib.remote.ConcurrentMapFactory;
import com.google.devtools.build.lib.remote.ContentDigests;
import com.google.devtools.build.lib.remote.ContentDigests.ActionKey;
import com.google.devtools.build.lib.remote.ExecutionCacheServiceGrpc.ExecutionCacheServiceImplBase;
import com.google.devtools.build.lib.remote.RemoteOptions;
import com.google.devtools.build.lib.remote.RemoteProtocol.ActionResult;
import com.google.devtools.build.lib.remote.RemoteProtocol.BlobChunk;
import com.google.devtools.build.lib.remote.RemoteProtocol.CasDownloadBlobRequest;
import com.google.devtools.build.lib.remote.RemoteProtocol.CasDownloadReply;
import com.google.devtools.build.lib.remote.RemoteProtocol.CasLookupReply;
import com.google.devtools.build.lib.remote.RemoteProtocol.CasLookupRequest;
import com.google.devtools.build.lib.remote.RemoteProtocol.CasStatus;
import com.google.devtools.build.lib.remote.RemoteProtocol.CasUploadBlobReply;
import com.google.devtools.build.lib.remote.RemoteProtocol.CasUploadBlobRequest;
import com.google.devtools.build.lib.remote.RemoteProtocol.CasUploadTreeMetadataReply;
import com.google.devtools.build.lib.remote.RemoteProtocol.CasUploadTreeMetadataRequest;
import com.google.devtools.build.lib.remote.RemoteProtocol.ContentDigest;
import com.google.devtools.build.lib.remote.RemoteProtocol.ExecutionCacheReply;
import com.google.devtools.build.lib.remote.RemoteProtocol.ExecutionCacheRequest;
import com.google.devtools.build.lib.remote.RemoteProtocol.ExecutionCacheSetReply;
import com.google.devtools.build.lib.remote.RemoteProtocol.ExecutionCacheSetRequest;
import com.google.devtools.build.lib.remote.RemoteProtocol.ExecutionCacheStatus;
import com.google.devtools.build.lib.remote.RemoteProtocol.FileNode;
import com.google.devtools.build.lib.util.Preconditions;
import com.google.devtools.common.options.OptionsParser;
import com.google.protobuf.ByteString;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.logging.Level;
import java.util.logging.Logger;

/** A server which acts as a gRPC wrapper around concurrent map based remote cache. */
public class RemoteCache {
  private static final Logger LOG = Logger.getLogger(RemoteWorker.class.getName());
  private static final boolean LOG_FINER = LOG.isLoggable(Level.FINER);
  private static final int MAX_MEMORY_KBYTES = 512 * 1024;
  private final ConcurrentMapActionCache cache;
  private final CasServiceImplBase casServer;
  private final ExecutionCacheServiceImplBase execCacheServer;

  public RemoteCache(ConcurrentMapActionCache cache) {
    this.cache = cache;
    casServer = new CasServer();
    execCacheServer = new ExecutionCacheServer();
  }

  public CasServiceImplBase getCasServer() {
    return casServer;
  }

  public ExecutionCacheServiceImplBase getExecCacheServer() {
    return execCacheServer;
  }

  class CasServer extends CasServiceImplBase {
    @Override
    public void lookup(CasLookupRequest request, StreamObserver<CasLookupReply> responseObserver) {
      CasLookupReply.Builder reply = CasLookupReply.newBuilder();
      CasStatus.Builder status = reply.getStatusBuilder();
      for (ContentDigest digest : request.getDigestList()) {
        if (!cache.containsKey(digest)) {
          status.addMissingDigest(digest);
        }
      }
      if (status.getMissingDigestCount() > 0) {
        status.setSucceeded(false);
        status.setError(CasStatus.ErrorCode.MISSING_DIGEST);
      } else {
        status.setSucceeded(true);
      }
      responseObserver.onNext(reply.build());
      responseObserver.onCompleted();
    }

    @Override
    public void uploadTreeMetadata(
        CasUploadTreeMetadataRequest request,
        StreamObserver<CasUploadTreeMetadataReply> responseObserver) {
      try {
        for (FileNode treeNode : request.getTreeNodeList()) {
          cache.uploadBlob(treeNode.toByteArray());
        }
        responseObserver.onNext(
            CasUploadTreeMetadataReply.newBuilder()
                .setStatus(CasStatus.newBuilder().setSucceeded(true))
                .build());
      } catch (Exception e) {
        LOG.warning("Request failed: " + e.toString());
        CasUploadTreeMetadataReply.Builder reply = CasUploadTreeMetadataReply.newBuilder();
        reply
            .getStatusBuilder()
            .setSucceeded(false)
            .setError(CasStatus.ErrorCode.UNKNOWN)
            .setErrorDetail(e.toString());
        responseObserver.onNext(reply.build());
      } finally {
        responseObserver.onCompleted();
      }
    }

    @Override
    public void downloadBlob(
        CasDownloadBlobRequest request, StreamObserver<CasDownloadReply> responseObserver) {
      CasDownloadReply.Builder reply = CasDownloadReply.newBuilder();
      CasStatus.Builder status = reply.getStatusBuilder();
      for (ContentDigest digest : request.getDigestList()) {
        if (!cache.containsKey(digest)) {
          status.addMissingDigest(digest);
        }
      }
      if (status.getMissingDigestCount() > 0) {
        status.setSucceeded(false);
        status.setError(CasStatus.ErrorCode.MISSING_DIGEST);
        responseObserver.onNext(reply.build());
        responseObserver.onCompleted();
        return;
      }
      status.setSucceeded(true);
      try {
        for (ContentDigest digest : request.getDigestList()) {
          reply.setData(
              BlobChunk.newBuilder()
                  .setDigest(digest)
                  .setData(ByteString.copyFrom(cache.downloadBlob(digest)))
                  .build());
          responseObserver.onNext(reply.build());
          if (reply.hasStatus()) {
            reply.clearStatus(); // Only send status on first chunk.
          }
        }
      } catch (CacheNotFoundException e) {
        // This can only happen if an item gets evicted right after we check.
        reply.clearData();
        status.setSucceeded(false);
        status.setError(CasStatus.ErrorCode.MISSING_DIGEST);
        status.addMissingDigest(e.getMissingDigest());
        responseObserver.onNext(reply.build());
      } finally {
        responseObserver.onCompleted();
      }
    }

    @Override
    public StreamObserver<CasUploadBlobRequest> uploadBlob(
        final StreamObserver<CasUploadBlobReply> responseObserver) {
      return new StreamObserver<CasUploadBlobRequest>() {
        byte[] blob = null;
        ContentDigest digest = null;
        long offset = 0;

        @Override
        public void onNext(CasUploadBlobRequest request) {
          BlobChunk chunk = request.getData();
          try {
            if (chunk.hasDigest()) {
              // Check if the previous chunk was really done.
              Preconditions.checkArgument(
                  digest == null || offset == 0,
                  "Missing input chunk for digest %s",
                  digest == null ? "" : ContentDigests.toString(digest));
              digest = chunk.getDigest();
              // This unconditionally downloads the whole blob into memory!
              Preconditions.checkArgument((int) (digest.getSizeBytes() / 1024) < MAX_MEMORY_KBYTES);
              blob = new byte[(int) digest.getSizeBytes()];
            }
            Preconditions.checkArgument(digest != null, "First chunk contains no digest");
            Preconditions.checkArgument(
                offset == chunk.getOffset(),
                "Missing input chunk for digest %s",
                ContentDigests.toString(digest));
            if (digest.getSizeBytes() > 0) {
              chunk.getData().copyTo(blob, (int) offset);
              offset = (offset + chunk.getData().size()) % digest.getSizeBytes();
            }
            if (offset == 0) {
              ContentDigest uploadedDigest = cache.uploadBlob(blob);
              Preconditions.checkArgument(
                  uploadedDigest.equals(digest),
                  "Digest mismatch: client sent %s, server computed %s",
                  ContentDigests.toString(digest),
                  ContentDigests.toString(uploadedDigest));
            }
          } catch (Exception e) {
            LOG.warning("Request failed: " + e.toString());
            CasUploadBlobReply.Builder reply = CasUploadBlobReply.newBuilder();
            reply
                .getStatusBuilder()
                .setSucceeded(false)
                .setError(
                    e instanceof IllegalArgumentException
                        ? CasStatus.ErrorCode.INVALID_ARGUMENT
                        : CasStatus.ErrorCode.UNKNOWN)
                .setErrorDetail(e.toString());
            responseObserver.onNext(reply.build());
          }
        }

        @Override
        public void onError(Throwable t) {
          LOG.warning("Request errored remotely: " + t);
        }

        @Override
        public void onCompleted() {
          responseObserver.onCompleted();
        }
      };
    }
  }

  class ExecutionCacheServer extends ExecutionCacheServiceImplBase {
    @Override
    public void getCachedResult(
        ExecutionCacheRequest request, StreamObserver<ExecutionCacheReply> responseObserver) {
      try {
        ActionKey actionKey = ContentDigests.unsafeActionKeyFromDigest(request.getActionDigest());
        ExecutionCacheReply.Builder reply = ExecutionCacheReply.newBuilder();
        ActionResult result = cache.getCachedActionResult(actionKey);
        if (result != null) {
          reply.setResult(result);
        }
        reply.getStatusBuilder().setSucceeded(true);
        responseObserver.onNext(reply.build());
      } catch (Exception e) {
        LOG.warning("getCachedActionResult request failed: " + e.toString());
        ExecutionCacheReply.Builder reply = ExecutionCacheReply.newBuilder();
        reply
            .getStatusBuilder()
            .setSucceeded(false)
            .setError(ExecutionCacheStatus.ErrorCode.UNKNOWN);
        responseObserver.onNext(reply.build());
      } finally {
        responseObserver.onCompleted();
      }
    }

    @Override
    public void setCachedResult(
        ExecutionCacheSetRequest request, StreamObserver<ExecutionCacheSetReply> responseObserver) {
      try {
        ActionKey actionKey = ContentDigests.unsafeActionKeyFromDigest(request.getActionDigest());
        cache.setCachedActionResult(actionKey, request.getResult());
        ExecutionCacheSetReply.Builder reply = ExecutionCacheSetReply.newBuilder();
        reply.getStatusBuilder().setSucceeded(true);
        responseObserver.onNext(reply.build());
      } catch (Exception e) {
        LOG.warning("setCachedActionResult request failed: " + e.toString());
        ExecutionCacheSetReply.Builder reply = ExecutionCacheSetReply.newBuilder();
        reply
            .getStatusBuilder()
            .setSucceeded(false)
            .setError(ExecutionCacheStatus.ErrorCode.UNKNOWN);
        responseObserver.onNext(reply.build());
      } finally {
        responseObserver.onCompleted();
      }
    }
  }

  public static void main(String[] args) throws Exception {
    OptionsParser parser =
        OptionsParser.newOptionsParser(RemoteOptions.class, RemoteWorkerOptions.class);
    parser.parseAndExitUponError(args);
    RemoteOptions remoteOptions = parser.getOptions(RemoteOptions.class);
    RemoteWorkerOptions remoteWorkerOptions = parser.getOptions(RemoteWorkerOptions.class);

    System.out.println("*** Starting Hazelcast server.");
    ConcurrentMapActionCache cache =
        new ConcurrentMapActionCache(ConcurrentMapFactory.createHazelcast(remoteOptions));

    System.out.println(
        "*** Starting grpc server on all locally bound IPs on port "
            + remoteWorkerOptions.listenPort
            + ".");
    RemoteCache worker = new RemoteCache(cache);
    final Server server =
        ServerBuilder.forPort(remoteWorkerOptions.listenPort)
            .addService(worker.getCasServer())
            .addService(worker.getExecCacheServer())
            .build();
    server.start();

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread() {
              @Override
              public void run() {
                System.err.println("*** Shutting down grpc server.");
                server.shutdown();
                System.err.println("*** Server shut down.");
              }
            });
    server.awaitTermination();
  }
}
